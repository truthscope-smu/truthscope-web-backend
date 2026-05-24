package com.truthscope.web.scoring;

public record CascadePolicy(
    int sourceCountThreshold,        // 3 (Tier 2 SCORABLE 기준)
    boolean tier1HitRequired,        // true (Tier 1 binary hit/miss)
    int criticalFieldCapPercent,     // 50
    java.util.List<String> claimSplitFields  // ["수치","일자","대상","금액","제도명"]
) {}
