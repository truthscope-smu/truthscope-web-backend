package com.truthscope.web.scoring;

/** Tier 2 cascade 의 단일 증거 source. stance = SUPPORTED/CONTRADICTED/NEUTRAL/UNRELATED. */
public record EvidenceSnapshot(
    String url,
    String publisher,
    String title,
    String stance,
    java.util.Map<String, String> matchedFields
) {}
