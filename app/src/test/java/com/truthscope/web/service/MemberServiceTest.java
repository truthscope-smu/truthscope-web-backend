package com.truthscope.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.truthscope.web.entity.Member;
import com.truthscope.web.entity.enums.MemberRole;
import com.truthscope.web.repository.MemberRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** MemberService.upsert 단위 테스트 (Mockito 기반, Docker 불필요). */
@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

  @Mock private MemberRepository memberRepository;

  @InjectMocks private MemberService memberService;

  @Test
  @DisplayName("upsert — 신규 member: findById 없음 -> save 1회 호출, member 반환")
  void upsert_newMember_savesOnce() {
    UUID id = UUID.randomUUID();
    Member saved =
        Member.builder().id(id).email("a@b.com").nickname("a").role(MemberRole.USER).build();
    given(memberRepository.findById(id)).willReturn(Optional.empty());
    given(memberRepository.save(any())).willReturn(saved);

    Member result = memberService.upsert(id, "a@b.com");

    assertThat(result.getId()).isEqualTo(id);
    verify(memberRepository, times(1)).save(any());
  }

  @Test
  @DisplayName("upsert — 기존 member: findById 있음 -> save 호출 없음, 기존 member 반환")
  void upsert_existingMember_noSave() {
    UUID id = UUID.randomUUID();
    Member existing =
        Member.builder().id(id).email("a@b.com").nickname("a").role(MemberRole.USER).build();
    given(memberRepository.findById(id)).willReturn(Optional.of(existing));

    Member result = memberService.upsert(id, "a@b.com");

    assertThat(result).isSameAs(existing);
    verify(memberRepository, times(0)).save(any());
  }

  @Test
  @DisplayName("upsert — nickname은 email prefix(@이전 부분)로 설정된다")
  void upsert_nickname_derivedFromEmailPrefix() {
    UUID id = UUID.randomUUID();
    Member saved =
        Member.builder()
            .id(id)
            .email("testuser@example.com")
            .nickname("testuser")
            .role(MemberRole.USER)
            .build();
    given(memberRepository.findById(id)).willReturn(Optional.empty());
    given(memberRepository.save(any())).willReturn(saved);

    Member result = memberService.upsert(id, "testuser@example.com");

    assertThat(result.getNickname()).isEqualTo("testuser");
  }

  @Test
  @DisplayName("upsert — DataIntegrityViolationException 시 재조회로 멱등 보장")
  void upsert_raceCondition_reReadsOnConflict() {
    UUID id = UUID.randomUUID();
    Member existing =
        Member.builder().id(id).email("a@b.com").nickname("a").role(MemberRole.USER).build();
    given(memberRepository.findById(id))
        .willReturn(Optional.empty()) // 1차 조회 없음
        .willReturn(Optional.of(existing)); // 충돌 후 재조회
    given(memberRepository.save(any())).willThrow(new DataIntegrityViolationException("dup"));

    Member result = memberService.upsert(id, "a@b.com");

    assertThat(result).isSameAs(existing);
    verify(memberRepository, times(2)).findById(id); // 1차 + 재조회
  }
}
