package com.truthscope.web.service;

import com.truthscope.web.entity.Member;
import com.truthscope.web.entity.enums.MemberRole;
import com.truthscope.web.repository.MemberRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 인증 사용자 member lazy upsert — 첫 인증 분석 시 호출, 이후엔 기존 row 반환. */
@Service
@RequiredArgsConstructor
public class MemberService {

  private final MemberRepository memberRepository;

  /**
   * Supabase sub(id)로 member를 조회하거나 신규 생성한다.
   *
   * <p>동시 첫 분석 race(같은 sub로 두 요청)에서 PK/email unique 충돌 시 DataIntegrityViolationException catch 후
   * 재조회로 멱등 보장.
   *
   * @param id Supabase Auth UUID (member PK)
   * @param email Supabase JWT에서 추출한 이메일
   * @return 기존 또는 신규 생성된 Member
   */
  @Transactional
  public Member upsert(UUID id, String email) {
    return memberRepository.findById(id).orElseGet(() -> saveNew(id, email));
  }

  private Member saveNew(UUID id, String email) {
    try {
      return memberRepository.save(
          Member.builder()
              .id(id)
              .email(email)
              .nickname(deriveNickname(email))
              .role(MemberRole.USER)
              .build());
    } catch (DataIntegrityViolationException e) {
      return memberRepository.findById(id).orElseThrow(() -> e); // 충돌인데 재조회도 없으면 진짜 에러
    }
  }

  private static String deriveNickname(String email) {
    if (email == null || email.isBlank()) return "user";
    int at = email.indexOf('@');
    return at > 0 ? email.substring(0, at) : email;
  }
}
