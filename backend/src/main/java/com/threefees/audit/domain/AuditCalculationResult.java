package com.threefees.audit.domain;

import java.math.BigDecimal;

public record AuditCalculationResult(
    AuditStatus status,
    OverLimitType overLimitType,
    BigDecimal actualEnergy,
    BigDecimal currentDailyEnergy,
    MetricResult yoy,
    MetricResult mom,
    MetricResult rated,
    BigDecimal maxRatioPercent) {}
