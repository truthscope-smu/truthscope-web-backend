package com.truthscope.web.scoring;

public record CascadePolicy(
    int sourceCountThreshold, // 1 (application.yml source-count-threshold=1 유효값)
    boolean tier1HitRequired, // true (Tier 1 binary hit/miss)
    int criticalFieldCapPercent, // 50
    java.util.List<String> claimSplitFields // ["수치","일자","대상","금액","제도명"]
    ) {}
