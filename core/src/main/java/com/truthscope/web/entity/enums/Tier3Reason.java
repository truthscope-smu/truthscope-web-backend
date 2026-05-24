package com.truthscope.web.entity.enums;

/** Tier 3 (검증 불가) reason 분류. score=NULL 일 때만 의미. BE GitHub issue #69 spec. */
public enum Tier3Reason {
  INSUFFICIENT,
  TIME_SENSITIVE,
  OUT_OF_SCOPE
}
