package com.truthscope.web.service.verification;

import com.truthscope.web.scoring.EvidenceSnapshot;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-016 Sparse+Dense skeleton. 본 phase = stub or minimal impl.
 *
 * <p>입력: claim text. 출력: List&lt;EvidenceSnapshot&gt; top-5 candidate.
 *
 * <p>Wave 2 = stub (fixture 반환) + integration test 에서 verified.
 *
 * <p>Sparse (Lucene BM25 placeholder) + Dense (embedding placeholder, ONNX 후속)
 *
 * <p>본 phase: fixture 반환. v2 트랙에서 실 구현 (ADR-021 §Sparse Lucene 인덱싱).
 */
@Service
public class HybridCascadeService {

  public HybridCascadeService() {
    // explicit no-arg constructor — dependencies will be injected in v2 impl track
  }

  /**
   * Retrieve evidence snapshots for a given claim.
   *
   * @param claimText the claim text to search evidence for
   * @param topK maximum number of evidence candidates to return
   * @return list of EvidenceSnapshot candidates (stub: always empty in this phase)
   */
  @Transactional(readOnly = true)
  public List<EvidenceSnapshot> retrieve(String claimText, int topK) {
    // Stub fixture — Sparse BM25 + Dense embedding impl deferred to ADR-016 v2 track
    return List.of();
  }
}
