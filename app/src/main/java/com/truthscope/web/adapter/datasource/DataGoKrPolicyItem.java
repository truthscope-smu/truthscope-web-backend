package com.truthscope.web.adapter.datasource;

import java.time.LocalDateTime;

/**
 * data.go.kr 정책뉴스/보도자료 어댑터가 반환하는 rich 타입.
 *
 * <p>PLAN.md T1 codex#2: DatasourceClaim(lossy)을 사용하지 않고 publisher/title/body를 보존한다.
 * FidelityClassifier가 publisher/title/body 3 필드를 필요로 하므로 정보 손실 없이 전달한다.
 *
 * @param url 원본 기사 URL (null/blank 항목은 파싱 단계에서 제외)
 * @param publisher 발행 기관명 (MinisterCode 매핑)
 * @param title 제목 (Title 필드)
 * @param body 본문 요약 (DataContents 또는 SubTitle1 매핑)
 * @param approveDate 승인/발행 일시 (ApproveDate "MM/dd/yyyy HH:mm:ss" 파싱)
 */
public record DataGoKrPolicyItem(
    String url, String publisher, String title, String body, LocalDateTime approveDate) {}
