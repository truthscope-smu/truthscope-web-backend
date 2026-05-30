package com.truthscope.web.adapter.datasource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * data.go.kr 정책뉴스(S3a) + 보도자료(S3a') 어댑터.
 *
 * <p>PLAN.md T1 결정 정합:
 *
 * <ul>
 *   <li>plain @Component — DataSourceAdapter 미구현 (codex#2: DatasourceClaim lossy 문제 해소).
 *   <li>@Transactional 없음 — 외부 HTTP는 트랜잭션 경계 밖 (RC-01 정합).
 *   <li>레포지토리 직접 접근 없음 — ArchitectureTest:303 adapter.datasource → repository 금지 준수.
 *   <li>RestClient.Builder unqualified 주입 — WikipediaAdapter 패턴 동일. per-request baseUrl().build().
 * </ul>
 *
 * <p>슬라이딩 윈도우: (endDate - startDate) > 3일 초과 시 <=3일 단위 분할 호출 후 url 기준 dedupe 머지. data-go-kr-api.md
 * §4 규칙 정합 (resultCode=98 THREE_DAYS_OVER_ERROR 방지).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DataGoKrAdapter {

  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final DateTimeFormatter APPROVE_DATE_FMT =
      DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss");

  // data.go.kr 실제 응답은 미선언 필드(NewsItemId/GroupingCode/ContentsStatus/ModifyDate 등, §5)를 포함하므로
  // FAIL_ON_UNKNOWN_PROPERTIES=false 필수. 미설정 시 모든 실응답이 파싱 예외 → 항상 빈 결과 → 항상 Tier 3
  // (T10 통합테스트 stub에 실 필드 포함시켜 회귀 검출).
  private static final XmlMapper XML_MAPPER =
      XmlMapper.builder()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .build();

  private final RestClient.Builder restClientBuilder;

  @Value(
      "${truthscope.datasource.policy-news.base-url:http://apis.data.go.kr/1371000/policyNewsService}")
  private String policyNewsBaseUrl;

  @Value(
      "${truthscope.datasource.press-release.base-url:http://apis.data.go.kr/1371000/pressReleaseService}")
  private String pressReleaseBaseUrl;

  @Value("${truthscope.datasource.data-go-kr-key:}")
  private String serviceKey;

  /**
   * 정책뉴스 + 보도자료 두 API를 모두 호출하고 머지한 결과를 반환한다.
   *
   * <p>날짜 범위가 3일(차이값 기준)을 초과하면 내부에서 <=3일 슬라이딩 윈도우로 분할 호출한다. url 기준 dedupe 적용. 외부 호출 실패/빈결과 시 빈 List
   * 반환 — 예외를 던지지 않는다 (Tier 3 안전강하 정합).
   *
   * @param from 조회 시작일 (포함)
   * @param to 조회 종료일 (포함)
   * @return dedupe된 DataGoKrPolicyItem 리스트
   */
  public List<DataGoKrPolicyItem> fetchPolicyItems(LocalDate from, LocalDate to) {
    // url → item dedupe 맵 (LinkedHashMap으로 삽입 순서 유지)
    Map<String, DataGoKrPolicyItem> dedupe = new LinkedHashMap<>();

    List<LocalDate[]> windows = buildWindows(from, to);
    for (LocalDate[] window : windows) {
      LocalDate start = window[0];
      LocalDate end = window[1];

      // 정책뉴스
      List<DataGoKrPolicyItem> policyItems =
          fetchWindow(policyNewsBaseUrl + "/policyNewsList", start, end);
      for (DataGoKrPolicyItem item : policyItems) {
        if (item.url() != null && !item.url().isBlank()) {
          dedupe.putIfAbsent(item.url(), item);
        }
      }

      // 보도자료
      List<DataGoKrPolicyItem> pressItems =
          fetchWindow(pressReleaseBaseUrl + "/pressReleaseList", start, end);
      for (DataGoKrPolicyItem item : pressItems) {
        if (item.url() != null && !item.url().isBlank()) {
          dedupe.putIfAbsent(item.url(), item);
        }
      }
    }

    return new ArrayList<>(dedupe.values());
  }

  /**
   * 단일 윈도우(<=3일) + 단일 endpoint에 대해 GET 요청 후 파싱 결과를 반환한다.
   *
   * <p>패키지-프라이빗(default) — DataGoKrAdapterTest에서 직접 접근 가능.
   */
  List<DataGoKrPolicyItem> fetchWindow(String endpoint, LocalDate start, LocalDate end) {
    try {
      String encodedKey = URLEncoder.encode(serviceKey, StandardCharsets.UTF_8);
      String startDateStr = start.format(DATE_FMT);
      String endDateStr = end.format(DATE_FMT);
      String url =
          endpoint
              + "?serviceKey="
              + encodedKey
              + "&startDate="
              + startDateStr
              + "&endDate="
              + endDateStr;

      RestClient client = restClientBuilder.baseUrl(endpoint).build();
      String xml =
          client
              .get()
              .uri(
                  "?serviceKey={key}&startDate={start}&endDate={end}",
                  encodedKey,
                  startDateStr,
                  endDateStr)
              .retrieve()
              .body(String.class);

      if (xml == null || xml.isBlank()) {
        log.warn("DataGoKrAdapter: 빈 응답. endpoint={} start={} end={}", endpoint, start, end);
        return List.of();
      }
      return parseXml(xml);

    } catch (RestClientException ex) {
      log.warn(
          "DataGoKrAdapter: HTTP 오류. endpoint={} start={} end={} error={}",
          endpoint,
          start,
          end,
          ex.getMessage());
      return List.of();
    } catch (Exception ex) {
      log.warn(
          "DataGoKrAdapter: 예상치 못한 오류. endpoint={} start={} end={} error={}",
          endpoint,
          start,
          end,
          ex.getMessage());
      return List.of();
    }
  }

  /**
   * XML 응답 문자열을 파싱하여 DataGoKrPolicyItem 리스트를 반환한다.
   *
   * <p>resultCode 분기 (data-go-kr-api.md §6):
   *
   * <ul>
   *   <li>0: 정상 — NewsItem 파싱 후 반환.
   *   <li>3: NODATA — 빈 리스트 반환 (정상 흐름).
   *   <li>1/2/5: Transient 오류 — log warn + 빈 리스트 반환 (재시도는 호출자 책임).
   *   <li>98: THREE_DAYS_OVER — 윈도우 분할 누락 코드 버그. log error + 빈 리스트.
   *   <li>30/32: Auth 오류 — log error + 빈 리스트.
   *   <li>그 외: log warn + 빈 리스트.
   * </ul>
   *
   * <p>패키지-프라이빗(default) — 테스트에서 직접 접근 가능.
   */
  List<DataGoKrPolicyItem> parseXml(String xml) {
    try {
      PolicyResponse response = XML_MAPPER.readValue(xml, PolicyResponse.class);

      if (response == null) {
        log.warn("DataGoKrAdapter.parseXml: XmlMapper가 null 반환");
        return List.of();
      }

      // resultCode 분기
      String resultCode = response.header != null ? response.header.resultCode : null;
      if (resultCode == null) {
        log.warn("DataGoKrAdapter.parseXml: resultCode 없음. xml 앞부분={}", safePrefix(xml));
        return List.of();
      }

      switch (resultCode) {
        case "0":
          // 정상 — 아래에서 NewsItem 처리
          break;
        case "3":
          log.debug("DataGoKrAdapter.parseXml: NODATA_ERROR (resultCode=3) — 빈 리스트 반환");
          return List.of();
        case "1":
        case "2":
        case "5":
          log.warn("DataGoKrAdapter.parseXml: Transient 오류 (resultCode={}) — 빈 리스트 반환", resultCode);
          return List.of();
        case "98":
          log.error(
              "DataGoKrAdapter.parseXml: THREE_DAYS_OVER_ERROR (resultCode=98)"
                  + " — 슬라이딩 윈도우 분할 누락 코드 버그. 빈 리스트 반환");
          return List.of();
        case "30":
        case "32":
          log.error(
              "DataGoKrAdapter.parseXml: Auth 오류 (resultCode={}) — serviceKey/IP 등록 확인 필요."
                  + " 빈 리스트 반환",
              resultCode);
          return List.of();
        default:
          log.warn("DataGoKrAdapter.parseXml: 알 수 없는 resultCode={}. 빈 리스트 반환", resultCode);
          return List.of();
      }

      // resultCode=0 정상 처리
      if (response.body == null || response.body.newsItems == null) {
        return List.of();
      }

      List<DataGoKrPolicyItem> result = new ArrayList<>();
      for (NewsItem item : response.body.newsItems) {
        // url 없는 항목 제외
        String url = item.originalUrl;
        if (url == null || url.isBlank()) {
          continue;
        }

        // body: DataContents 우선, 없으면 SubTitle1
        String body = item.dataContents;
        if (body == null || body.isBlank()) {
          body = item.subTitle1;
        }

        // approveDate 파싱 (MM/dd/yyyy HH:mm:ss)
        LocalDateTime approveDate = null;
        if (item.approveDate != null && !item.approveDate.isBlank()) {
          try {
            approveDate = LocalDateTime.parse(item.approveDate.trim(), APPROVE_DATE_FMT);
          } catch (Exception ex) {
            log.debug(
                "DataGoKrAdapter: approveDate 파싱 실패. value={} error={}",
                item.approveDate,
                ex.getMessage());
          }
        }

        result.add(new DataGoKrPolicyItem(url, item.ministerCode, item.title, body, approveDate));
      }
      return result;

    } catch (Exception ex) {
      log.warn("DataGoKrAdapter.parseXml: 파싱 실패. error={}", ex.getMessage());
      return List.of();
    }
  }

  // ── 정적 fixture (테스트/로컬용) ──

  /** 테스트/로컬용 결정적 fixture. >=5건 반환. */
  public static List<DataGoKrPolicyItem> fixture() {
    LocalDateTime fixed = LocalDateTime.of(2026, 5, 8, 16, 51, 0);
    return List.of(
        new DataGoKrPolicyItem(
            "https://www.korea.kr/news/policyNewsView.do?newsId=148964101",
            "산업통상자원부",
            "2026년 에너지 전환 정책 현황",
            "정부는 2026년 재생에너지 비중을 30%까지 높이는 로드맵을 발표했다.",
            fixed),
        new DataGoKrPolicyItem(
            "https://www.korea.kr/news/pressReleaseView.do?newsId=148964102",
            "과학기술정보통신부",
            "AI 반도체 국산화 지원 보도자료",
            "과기정통부는 AI 반도체 개발 기업에 500억 원 규모의 지원책을 마련했다.",
            fixed.minusDays(1)),
        new DataGoKrPolicyItem(
            "https://www.korea.kr/news/policyNewsView.do?newsId=148964103",
            "보건복지부",
            "2026 국민건강보험 보장성 강화 계획",
            "희귀질환 및 중증질환 치료비 지원 범위를 확대한다.",
            fixed.minusDays(2)),
        new DataGoKrPolicyItem(
            "https://www.korea.kr/news/pressReleaseView.do?newsId=148964104",
            "기획재정부",
            "하반기 경제정책방향 발표",
            "내수 진작 및 수출 지원을 위한 종합 대책이 담겼다.",
            fixed.minusDays(2)),
        new DataGoKrPolicyItem(
            "https://www.korea.kr/news/policyNewsView.do?newsId=148964105",
            "환경부",
            "탄소중립 2050 세부 이행계획",
            "온실가스 감축 목표 달성을 위한 부문별 실행 방안을 담았다.",
            fixed.minusDays(3)));
  }

  // ── private helpers ──

  /** from~to 범위를 <=3일 윈도우로 분할한 리스트를 반환한다. */
  private static List<LocalDate[]> buildWindows(LocalDate from, LocalDate to) {
    List<LocalDate[]> windows = new ArrayList<>();
    LocalDate cursor = from;
    while (!cursor.isAfter(to)) {
      // 현재 커서에서 최대 3일 후까지 (차이값 <=3 = 4일치)
      LocalDate windowEnd = cursor.plusDays(3);
      if (windowEnd.isAfter(to)) {
        windowEnd = to;
      }
      windows.add(new LocalDate[] {cursor, windowEnd});
      cursor = windowEnd.plusDays(1);
    }
    return windows;
  }

  private static String safePrefix(String xml) {
    if (xml == null) return "(null)";
    return xml.length() > 200 ? xml.substring(0, 200) : xml;
  }

  // ── Jackson XML 파싱용 내부 DTO ──

  @JacksonXmlRootElement(localName = "response")
  static class PolicyResponse {
    @JacksonXmlProperty(localName = "header")
    public ResponseHeader header;

    @JacksonXmlProperty(localName = "body")
    public ResponseBody body;
  }

  static class ResponseHeader {
    @JacksonXmlProperty(localName = "resultCode")
    public String resultCode;

    @JacksonXmlProperty(localName = "resultMsg")
    public String resultMsg;
  }

  static class ResponseBody {
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "NewsItem")
    public List<NewsItem> newsItems;
  }

  static class NewsItem {
    @JacksonXmlProperty(localName = "Title")
    public String title;

    @JacksonXmlProperty(localName = "SubTitle1")
    public String subTitle1;

    @JacksonXmlProperty(localName = "DataContents")
    public String dataContents;

    @JacksonXmlProperty(localName = "MinisterCode")
    public String ministerCode;

    @JacksonXmlProperty(localName = "OriginalUrl")
    public String originalUrl;

    @JacksonXmlProperty(localName = "ApproveDate")
    public String approveDate;

    // 보도자료 전용 첨부파일 (0..n) — 파싱은 하되 DataGoKrPolicyItem에는 포함 안 함
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "FileName")
    public List<String> fileNames;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "FileUrl")
    public List<String> fileUrls;
  }
}
