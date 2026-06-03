package com.truthscope.web.adapter.verification;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * claimText 에서 발표일 regex 추출 후 날짜 윈도우를 결정한다 (T4 helper — adapter.verification 패키지).
 *
 * <p>추출 패턴: yyyy년 M월 d일 / yyyy-MM-dd / yyyy.MM.dd. 추출 실패 시 today 기준 [today-3, today] 반환.
 *
 * <p>ArchUnit serviceNaming: service.* 하위가 아닌 adapter.verification 패키지. @Component — Spring bean.
 */
@Component
public class EvidenceWindowResolver {

  /** 윈도우 시작일 + 종료일. */
  public record Window(LocalDate from, LocalDate to) {}

  // yyyy년 MM월 dd일 (한국어 날짜 표기, 공백 허용)
  private static final Pattern KO_DATE = Pattern.compile("(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일");

  // yyyy-MM-dd 또는 yyyy.MM.dd
  private static final Pattern ISO_DATE =
      Pattern.compile("(\\d{4})[-\\.](\\d{1,2})[-\\.](\\d{1,2})");

  /**
   * claimText 에서 발표일을 추출하여 [date-3, date] 윈도우를 반환한다. 추출 실패 시 [today-3, today] 반환.
   *
   * @param claimText 검증 대상 claim 텍스트
   * @return 윈도우 (from 포함, to 포함, 최대 3일 차이)
   */
  public Window resolve(String claimText) {
    return resolve(claimText, null);
  }

  /**
   * 윈도우 기준일 우선순위: claimText 추출 날짜 우선, 없으면 기사 발행일(fallbackDate), 그것도 없으면 today.
   *
   * <p>기사 발행일을 기준으로 두면 과거 기사(예: 2025-12 발행)도 발행 시점의 data.go.kr 원문을 검색한다. today 폴백만 쓰면 발행 시점과 어긋나 매칭
   * 0건(INSUFFICIENT)이 된다.
   *
   * @param claimText 검증 대상 claim 텍스트
   * @param fallbackDate 기사 발행일 (nullable). claimText 에 날짜가 없을 때 기준일로 사용.
   * @return 윈도우 (from 포함, to 포함, 최대 3일 차이)
   */
  public Window resolve(String claimText, LocalDate fallbackDate) {
    LocalDate base = (claimText != null) ? tryExtract(claimText) : null;
    if (base == null) {
      base = fallbackDate;
    }
    if (base == null) {
      base = LocalDate.now();
    }
    return new Window(base.minusDays(3), base);
  }

  private LocalDate tryExtract(String text) {
    Matcher koMatcher = KO_DATE.matcher(text);
    if (koMatcher.find()) {
      return parseDate(koMatcher.group(1), koMatcher.group(2), koMatcher.group(3));
    }
    Matcher isoMatcher = ISO_DATE.matcher(text);
    if (isoMatcher.find()) {
      return parseDate(isoMatcher.group(1), isoMatcher.group(2), isoMatcher.group(3));
    }
    return null;
  }

  private LocalDate parseDate(String yearStr, String monthStr, String dayStr) {
    try {
      int year = Integer.parseInt(yearStr);
      int month = Integer.parseInt(monthStr);
      int day = Integer.parseInt(dayStr);
      if (year < 2000 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31) {
        return null;
      }
      return LocalDate.of(year, month, day);
    } catch (Exception e) {
      return null;
    }
  }
}
