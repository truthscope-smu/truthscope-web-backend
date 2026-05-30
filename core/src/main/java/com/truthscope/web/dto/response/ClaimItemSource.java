package com.truthscope.web.dto.response;

import com.truthscope.web.entity.Claim;
import com.truthscope.web.entity.VerificationResult;
import com.truthscope.web.entity.VerifySource;
import java.util.List;

/**
 * claim별 검증 결과 조립 입력 레코드 (ArticleVerificationConverter 입력).
 *
 * <p>응답 조립 내부용 — 직렬화 대상이 아니다. 서비스(app)가 도출값(truthLabel/claimScoreStatus)을 계산해 채운 뒤 Converter(core)에
 * 전달한다. converter 패키지에 두면 ArchUnit converterNaming 룰(클래스명 *Converter 강제)에 걸리므로 dto.response에 둔다.
 *
 * @param claim Claim 엔티티 (non-null)
 * @param result VerificationResult 엔티티, null이면 미검증 claim
 * @param truthLabel TruthLabel.name() 도출값, SCORABLE이고 score!=null인 경우만 non-null
 * @param claimScoreStatus ClaimScoreStatus.name() 도출값, SCORABLE이면 null (truthLabel과 상호 배타)
 * @param sources 이 claim에 해당하는 VerifySource 목록 (66b T8, VerifySource bulk 조회 결과)
 */
public record ClaimItemSource(
    Claim claim,
    VerificationResult result,
    String truthLabel,
    String claimScoreStatus,
    List<VerifySource> sources) {}
