package com.threefees.audit.domain;

import java.math.BigDecimal;
import java.time.YearMonth;

public record AuditCalculationInput(
    YearMonth period,
    boolean currentPaymentEligible,
    BigDecimal actualEnergy,
    int currentPaymentDays,
    BigDecimal currentBenchmarkTotal,
    ReferencePeriod yoyReference,
    ReferencePeriod momReference) {

  public AuditCalculationInput(
      YearMonth period,
      boolean currentPaymentEligible,
      BigDecimal actualEnergy,
      BigDecimal currentBenchmarkTotal,
      ReferencePeriod yoyReference,
      ReferencePeriod momReference) {
    this(
        period,
        currentPaymentEligible,
        actualEnergy,
        period.lengthOfMonth(),
        currentBenchmarkTotal,
        yoyReference,
        momReference);
  }

  public record ReferencePeriod(
      YearMonth period,
      boolean paymentEligible,
      BigDecimal actualEnergy,
      int paymentDays,
      BigDecimal benchmarkTotal) {

    public ReferencePeriod(
        YearMonth period,
        boolean paymentEligible,
        BigDecimal actualEnergy,
        BigDecimal benchmarkTotal) {
      this(period, paymentEligible, actualEnergy, period.lengthOfMonth(), benchmarkTotal);
    }
  }
}
