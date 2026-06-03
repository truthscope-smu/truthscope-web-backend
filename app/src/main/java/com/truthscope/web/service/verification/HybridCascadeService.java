package com.truthscope.web.service.verification;

import com.truthscope.web.adapter.datasource.DataGoKrAdapter;
import com.truthscope.web.adapter.datasource.DataGoKrPolicyItem;
import com.truthscope.web.adapter.datasource.NaverSearchAdapter;
import com.truthscope.web.adapter.verification.EvidencePrefilter;
import com.truthscope.web.adapter.verification.EvidenceWindowResolver;
import com.truthscope.web.scoring.EvidenceCandidate;
import com.truthscope.web.scoring.EvidenceSnapshot;
import com.truthscope.web.scoring.FidelityClassifierPort;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Tier 2 evidence 수집 서비스 (T4 실구현).
 *
 * <p>RC-01 결정: @Transactional 없음 — 외부 HTTP(data.go.kr + Gemini + Naver) 는 트랜잭션 바깥. retrieve() 는 DB
 * 미접근.
 *
 * <p>흐름: 1) EvidenceWindowResolver 로 날짜 윈도우 결정. 2) DataGoKrAdapter.fetchPolicyItems 로 정책뉴스/보도자료 수집.
 * 2b) NaverSearchAdapter.search 로 보조 Tier-1 출처 수집 후 URL dedupe 병합. 3) EvidencePrefilter.top 으로
 * claim 핵심어 기반 top-8 후보 추출. 4) FidelityClassifierPort.classify 로 SUPPORTED/CONTRADICTED +
 * matchedFields 보유분만 반환. 예외/빈결과 시 빈 List (Tier 3 안전강하).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HybridCascadeService {

  private final DataGoKrAdapter dataGoKrAdapter;
  private final NaverSearchAdapter naverSearchAdapter;
  private final FidelityClassifierPort fidelityClassifier;
  private final EvidenceWindowResolver windowResolver;
  private final EvidencePrefilter prefilter;

  /**
   * claimText 에 대한 evidence 목록을 반환한다 (기사 발행일 미지정 — claimText 날짜 또는 today 윈도우).
   *
   * @param claimText 검증 대상 claim 텍스트
   * @param topK 반환할 최대 evidence 수
   * @return EvidenceSnapshot 목록. 실패 시 빈 목록.
   */
  public List<EvidenceSnapshot> retrieve(String claimText, int topK) {
    return retrieve(claimText, topK, null);
  }

  /**
   * claimText 에 대한 evidence 목록을 반환한다.
   *
   * <p>@Transactional 없음 (RC-01 — 외부 HTTP 포함).
   *
   * @param claimText 검증 대상 claim 텍스트
   * @param topK 반환할 최대 evidence 수
   * @param fallbackDate 기사 발행일 (nullable). claimText 에 날짜가 없을 때 evidence 윈도우 기준일로 사용. null 이면 today
   *     기준. 과거 기사도 발행 시점의 data.go.kr 원문을 검색하게 하여 매칭 0건(INSUFFICIENT)을 방지한다.
   * @return stance SUPPORTED/CONTRADICTED 이고 matchedFields 비어 있지 않은 EvidenceSnapshot 목록. data.go.kr
   *     + Naver URL dedupe 병합 결과. 실패 시 빈 목록.
   */
  public List<EvidenceSnapshot> retrieve(String claimText, int topK, LocalDate fallbackDate) {
    try {
      // 1) 날짜 윈도우 결정 (claimText 날짜 우선, 없으면 기사 발행일, 그것도 없으면 today)
      EvidenceWindowResolver.Window window = windowResolver.resolve(claimText, fallbackDate);
      LocalDate from = window.from();
      LocalDate to = window.to();

      // 2) data.go.kr 정책뉴스 + 보도자료 수집 (날짜 덤프)
      List<DataGoKrPolicyItem> items = dataGoKrAdapter.fetchPolicyItems(from, to);

      // 2b) Naver 뉴스 검색 (보조 Tier-1 출처) — URL 기준 dedupe 병합.
      // Naver 는 날짜 윈도우 밖 post-retrieval 병합 경로이므로 window 미적용이 아키텍처상 정상이다.
      List<DataGoKrPolicyItem> naverItems = naverSearchAdapter.search(claimText);
      LinkedHashMap<String, DataGoKrPolicyItem> mergeMap = new LinkedHashMap<>();
      for (DataGoKrPolicyItem it : items) {
        if (it.url() != null && !it.url().isBlank()) mergeMap.putIfAbsent(it.url(), it);
      }
      for (DataGoKrPolicyItem it : naverItems) {
        if (it.url() != null && !it.url().isBlank()) mergeMap.putIfAbsent(it.url(), it);
      }
      List<DataGoKrPolicyItem> merged = new ArrayList<>(mergeMap.values());
      if (merged.isEmpty()) {
        return List.of();
      }

      // 3) claim 핵심어 기반 lexical top-8 prefilter + EvidenceCandidate 변환
      List<EvidenceCandidate> cands = prefilter.top(claimText, merged, 8);
      if (cands.isEmpty()) {
        return List.of();
      }

      // 4) FidelityClassifierPort: classify → SUPPORTED/CONTRADICTED + matchedFields 보유분만 반환
      return fidelityClassifier.classify(claimText, cands, null);

    } catch (Exception ex) {
      log.warn(
          "HybridCascadeService.retrieve: 예외 발생, Tier 3 안전강하. claimText 앞부분={} error={}",
          claimText != null && claimText.length() > 50 ? claimText.substring(0, 50) : claimText,
          ex.getMessage());
      return List.of();
    }
  }
}
