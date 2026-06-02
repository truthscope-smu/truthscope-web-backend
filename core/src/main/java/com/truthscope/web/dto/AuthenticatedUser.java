package com.truthscope.web.dto;

import java.util.UUID;

/** 인증된 사용자 정보 (Supabase JWT sub + email). 보안 패키지 의존 없음 — core 모듈 배치 가능. */
public record AuthenticatedUser(UUID id, String email) {}
