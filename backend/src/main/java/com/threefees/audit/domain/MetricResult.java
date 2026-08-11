package com.threefees.audit.domain;

import java.math.BigDecimal;

public record MetricResult(
    boolean applicable,
    boolean overLimit,
    BigDecimal actual,
    BigDecimal threshold,
    BigDecimal ratioPercent,
    String note) {

  public static MetricResult notApplicable(String note) {
    return new MetricResult(false, false, null, null, null, note);
  }
}
