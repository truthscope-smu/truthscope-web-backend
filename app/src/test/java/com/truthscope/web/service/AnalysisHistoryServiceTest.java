package com.truthscope.web.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.truthscope.web.dto.response.AnalysisSessionHistoryResponse;
import com.truthscope.web.entity.AnalysisSession;
import com.truthscope.web.entity.Article;
import com.truthscope.web.entity.Member;
import com.truthscope.web.entity.enums.MemberRole;
import com.truthscope.web.entity.enums.SessionStatus;
import com.truthscope.web.repository.AnalysisSessionRepository;
import com.truthscope.web.repository.ArticleRepository;
import com.truthscope.web.repository.MemberRepository;
import com.truthscope.web.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * findHistoryByMemberId JPQL 실행 검증 (H-1/CX-9).
 *
 * <p>Testcontainers PostgreSQL 필요 — Docker 없이 실행 시 자동 SKIP (@Testcontainers
 * disabledWithoutDocker=true).
 */
class AnalysisHistoryServiceTest extends AbstractIntegrationTest {

  @Autowired private AnalysisHistoryService analysisHistoryService;

  @Autowired private MemberRepository memberRepository;

  @Autowired private AnalysisSessionRepository sessionRepository;

  @Autowired private ArticleRepository articleRepository;

  @Test
  @DisplayName("findMySessions — member 격리: 타 member 세션 미포함")
  void findMySessions_isolatesByMember() {
    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    Member m1 = memberRepository.save(member(id1, "u1@test.com"));
    Member m2 = memberRepository.save(member(id2, "u2@test.com"));

    AnalysisSession s1 = sessionRepository.save(session(m1));
    sessionRepository.save(session(m2)); // 타 member

    List<AnalysisSessionHistoryResponse> result = analysisHistoryService.findMySessions(id1);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getSessionId()).isEqualTo(s1.getId());
  }

  @Test
  @DisplayName("findMySessions — requested_at DESC 정렬")
  void findMySessions_orderedByRequestedAtDesc() {
    UUID id = UUID.randomUUID();
    Member m = memberRepository.save(member(id, id + "@test.com"));

    AnalysisSession older = sessionRepository.save(sessionAt(m, LocalDateTime.now().minusHours(2)));
    AnalysisSession newer = sessionRepository.save(sessionAt(m, LocalDateTime.now()));

    List<AnalysisSessionHistoryResponse> result = analysisHistoryService.findMySessions(id);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getSessionId()).isEqualTo(newer.getId());
    assertThat(result.get(1).getSessionId()).isEqualTo(older.getId());
  }

  @Test
  @DisplayName("findMySessions — article 없는 세션(PENDING)도 포함, article 필드는 null")
  void findMySessions_pendingSessionWithoutArticle() {
    UUID id = UUID.randomUUID();
    Member m = memberRepository.save(member(id, id + "@test.com"));
    sessionRepository.save(session(m));

    List<AnalysisSessionHistoryResponse> result = analysisHistoryService.findMySessions(id);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getArticleId()).isNull();
    assertThat(result.get(0).getArticleTitle()).isNull();
  }

  @Test
  @DisplayName("findMySessions — article 있는 세션은 article 필드 채워져 반환")
  void findMySessions_sessionWithArticle() {
    UUID id = UUID.randomUUID();
    Member m = memberRepository.save(member(id, id + "@test.com"));
    AnalysisSession s = sessionRepository.save(session(m));
    Article article =
        Article.extract("https://news.example.com/1", "테스트 제목", "본문", "ko", "news.example.com")
            .attachTo(s);
    articleRepository.save(article);

    List<AnalysisSessionHistoryResponse> result = analysisHistoryService.findMySessions(id);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getArticleTitle()).isEqualTo("테스트 제목");
    assertThat(result.get(0).getArticleDomain()).isEqualTo("news.example.com");
  }

  // ─── helpers ───

  private static Member member(UUID id, String email) {
    return Member.builder().id(id).email(email).nickname("nick").role(MemberRole.USER).build();
  }

  private static AnalysisSession session(Member member) {
    return sessionAt(member, LocalDateTime.now());
  }

  private static AnalysisSession sessionAt(Member member, LocalDateTime requestedAt) {
    return AnalysisSession.builder()
        .member(member)
        .status(SessionStatus.PENDING)
        .requestedAt(requestedAt)
        .build();
  }
}
