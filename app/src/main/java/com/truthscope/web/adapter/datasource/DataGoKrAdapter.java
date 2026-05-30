package com.truthscope.web.adapter.datasource;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
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
 * data.go.kr 정책뉴스(S3a) + 보도자료(S3a') 어댑터. plain @Component, @Transactional 없음(RC-01). 슬라이딩
 * 윈도우(data-go-kr-api.md §4, resultCode=98 방지). DTO 클래스는 DataGoKrXmlResponse.java에 분리.
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
   * 정책뉴스 + 보도자료 API를 슬라이딩 윈도우(<=3일 단위)로 분할 호출해 url 기준 dedupe 머지 결과를 반환한다. 실패 시 빈 리스트 (Tier 3 안전강하).
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

  /** 단일 윈도우(<=3일) + 단일 endpoint에 대해 GET 요청 후 파싱 결과를 반환한다. 패키지-프라이빗. */
  List<DataGoKrPolicyItem> fetchWindow(String endpoint, LocalDate start, LocalDate end) {
    try {
      String encodedKey = URLEncoder.encode(serviceKey, StandardCharsets.UTF_8);
      String startDateStr = start.format(DATE_FMT);
      String endDateStr = end.format(DATE_FMT);
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
   * XML 응답 문자열을 파싱하여 DataGoKrPolicyItem 리스트를 반환한다. resultCode 분기는 handleResultCodeBranch() 참조.
   * 패키지-프라이빗 — 테스트에서 직접 접근 가능.
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

      List<DataGoKrPolicyItem> branchResult = handleResultCodeBranch(resultCode);
      if (branchResult != null) {
        return branchResult;
      }

      // resultCode=0 정상 처리
      if (response.body == null || response.body.newsItems == null) {
        return List.of();
      }
      return mapNewsItems(response.body.newsItems);

    } catch (Exception ex) {
      log.warn("DataGoKrAdapter.parseXml: 파싱 실패. error={}", ex.getMessage());
      return List.of();
    }
  }

  /** resultCode 분기. 0(정상)이면 null 반환, 그 외 해당 빈/오류 결과를 반환한다. */
  private List<DataGoKrPolicyItem> handleResultCodeBranch(String resultCode) {
    switch (resultCode) {
      case "0":
        // 정상 — 호출자에서 NewsItem 처리
        return null;
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
  }

  /** NewsItem 목록을 DataGoKrPolicyItem 목록으로 변환한다. url 없는 항목은 제외한다. */
  private List<DataGoKrPolicyItem> mapNewsItems(List<PolicyResponse.NewsItem> newsItems) {
    List<DataGoKrPolicyItem> result = new ArrayList<>();
    for (PolicyResponse.NewsItem item : newsItems) {
      String url = item.originalUrl;
      if (url == null || url.isBlank()) {
        continue;
      }
      String body = item.dataContents;
      if (body == null || body.isBlank()) {
        body = item.subTitle1;
      }
      LocalDateTime approveDate = parseApproveDate(item.approveDate);
      result.add(new DataGoKrPolicyItem(url, item.ministerCode, item.title, body, approveDate));
    }
    return result;
  }

  /** approveDate 문자열을 파싱한다. 실패 시 null 반환 (로그 debug). */
  private LocalDateTime parseApproveDate(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDateTime.parse(raw.trim(), APPROVE_DATE_FMT);
    } catch (Exception ex) {
      log.debug("DataGoKrAdapter: approveDate 파싱 실패. value={} error={}", raw, ex.getMessage());
      return null;
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
}
