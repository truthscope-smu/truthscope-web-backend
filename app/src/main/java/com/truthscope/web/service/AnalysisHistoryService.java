package com.truthscope.web.service;

import com.truthscope.web.converter.AnalysisSessionHistoryConverter;
import com.truthscope.web.dto.response.AnalysisSessionHistoryResponse;
import com.truthscope.web.repository.AnalysisSessionRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 인증 사용자 분석 이력 조회 서비스. */
@Service
@RequiredArgsConstructor
public class AnalysisHistoryService {

  private final AnalysisSessionRepository sessionRepository;

  /**
   * member의 분석 이력을 요청일 내림차순으로 반환한다.
   *
   * @param memberId Supabase Auth UUID (member PK)
   */
  @Transactional(readOnly = true)
  public List<AnalysisSessionHistoryResponse> findMySessions(UUID memberId) {
    return sessionRepository.findHistoryByMemberId(memberId).stream()
        .map(AnalysisSessionHistoryConverter::toResponse)
        .toList();
  }
}
