package com.threefees.audit.domain;

import java.math.BigDecimal;
import java.time.YearMonth;

public record AuditCalculationInput(
    YearMonth period,
    boolean currentPaymentEligible,
    BigDecimal actualEnergy,
    BigDecimal currentBenchmarkTotal,
    ReferencePeriod yoyReference,
    ReferencePeriod momReference) {

  public record ReferencePeriod(
      YearMonth period,
      boolean paymentEligible,
      BigDecimal actualEnergy,
      BigDecimal benchmarkTotal) {}
}
