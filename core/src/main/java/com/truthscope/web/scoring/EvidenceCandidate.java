package com.truthscope.web.scoring;

/** Tier 2 fidelity 분류기 입력 후보 — DataGoKrAdapter 에서 prefilter 를 거쳐 전달됨. */
public record EvidenceCandidate(String url, String publisher, String title, String body) {}
