package com.threefees.audit.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AuditCalculator {

  private static final BigDecimal HISTORICAL_MARGIN = new BigDecimal("1.20");
  private static final MathContext INTERNAL_CONTEXT = MathContext.DECIMAL128;
  private static final int FORMULA_SCALE = 6;

  public AuditCalculationResult calculate(AuditCalculationInput input) {
    if (input.actualEnergy() == null) {
      MetricResult notApplicable = MetricResult.notApplicable("当前实际用电缺失");
      return new AuditCalculationResult(
          AuditStatus.NOT_APPLICABLE,
          OverLimitType.NONE,
          input.actualEnergy(),
          null,
          notApplicable,
          notApplicable,
          notApplicable,
          null);
    }

    BigDecimal currentDaily =
        input.currentDailyEnergy() == null
            ? null
            : input.currentDailyEnergy().setScale(FORMULA_SCALE, RoundingMode.HALF_UP);
    MetricResult yoy =
        historicalMetric(
            currentDaily,
            input.currentPaymentDays(),
            input.currentPaymentEligible(),
            input.currentBenchmarkTotal(),
            input.yoyReference(),
            "同比");
    MetricResult mom =
        historicalMetric(
            currentDaily,
            input.currentPaymentDays(),
            input.currentPaymentEligible(),
            input.currentBenchmarkTotal(),
            input.momReference(),
            "环比");
    MetricResult rated = ratedMetric(input.actualEnergy(), input.currentBenchmarkTotal());
    List<MetricResult> applicable =
        List.of(yoy, mom, rated).stream().filter(MetricResult::applicable).toList();
    if (applicable.isEmpty()) {
      return new AuditCalculationResult(
          AuditStatus.NOT_APPLICABLE,
          OverLimitType.NONE,
          input.actualEnergy(),
          currentDaily,
          yoy,
          mom,
          rated,
          null);
    }
    var exceededNames = new ArrayList<OverLimitType>();
    if (yoy.overLimit()) {
      exceededNames.add(OverLimitType.ONLY_YOY);
    }
    if (mom.overLimit()) {
      exceededNames.add(OverLimitType.ONLY_MOM);
    }
    if (rated.overLimit()) {
      exceededNames.add(OverLimitType.ONLY_RATED);
    }
    AuditStatus status = exceededNames.isEmpty() ? AuditStatus.NORMAL : AuditStatus.OVER_LIMIT;
    OverLimitType overLimitType =
        exceededNames.isEmpty()
            ? OverLimitType.NONE
            : exceededNames.size() == 1 ? exceededNames.getFirst() : OverLimitType.MULTIPLE;
    BigDecimal maxRatio =
        applicable.stream()
            .filter(MetricResult::overLimit)
            .map(MetricResult::ratioPercent)
            .filter(java.util.Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
    return new AuditCalculationResult(
        status, overLimitType, input.actualEnergy(), currentDaily, yoy, mom, rated, maxRatio);
  }

  private MetricResult historicalMetric(
      BigDecimal currentDaily,
      int currentPaymentDays,
      boolean currentPaymentEligible,
      BigDecimal currentBenchmarkTotal,
      AuditCalculationInput.ReferencePeriod reference,
      String label) {
    if (!currentPaymentEligible) {
      return MetricResult.notApplicable(label + "需要当前账期存在审核通过的缴费明细");
    }
    if (currentBenchmarkTotal == null
        || reference == null
        || !reference.paymentEligible()
        || currentDaily == null
        || reference.dailyEnergy() == null
        || reference.benchmarkTotal() == null) {
      return MetricResult.notApplicable(label + "所需 A、B、C 或审核资格缺失");
    }
    if (currentPaymentDays <= 0 || reference.paymentDays() <= 0) {
      return MetricResult.notApplicable(label + "缴费时间缺失");
    }
    BigDecimal a = divideFormula(currentBenchmarkTotal, BigDecimal.valueOf(currentPaymentDays));
    BigDecimal b = divideFormula(reference.benchmarkTotal(), BigDecimal.valueOf(reference.paymentDays()));
    if (b.signum() <= 0) {
      return MetricResult.notApplicable(label + "参考缴费额定日均 B 小于等于 0");
    }
    BigDecimal c = reference.dailyEnergy().setScale(FORMULA_SCALE, RoundingMode.HALF_UP);
    BigDecimal quotient = a.divide(b, FORMULA_SCALE, RoundingMode.HALF_UP);
    BigDecimal k = quotient.compareTo(BigDecimal.ONE) > 0 ? quotient : BigDecimal.ONE.setScale(FORMULA_SCALE);
    BigDecimal threshold =
        c.multiply(k, INTERNAL_CONTEXT)
            .multiply(HISTORICAL_MARGIN, INTERNAL_CONTEXT)
            .setScale(2, RoundingMode.HALF_UP);
    boolean overLimit = currentDaily.compareTo(threshold) > 0;
    BigDecimal ratio = overLimit ? ratio(currentDaily, threshold) : BigDecimal.ZERO;
    String note = label + "正常上限 = 参考日均 C * max(1, A/B) * 1.20";
    if (threshold.signum() == 0 && overLimit) {
      ratio = null;
      note += "；正常上限为 0，比例不定义";
    }
    return new MetricResult(true, overLimit, currentDaily, threshold, ratio, note);
  }

  private MetricResult ratedMetric(BigDecimal actualEnergy, BigDecimal benchmarkTotal) {
    if (benchmarkTotal == null) {
      return MetricResult.notApplicable("当月额定功率标杆月总值缺失");
    }
    boolean overLimit = actualEnergy.compareTo(benchmarkTotal) > 0;
    if (benchmarkTotal.signum() == 0) {
      return new MetricResult(
          true,
          overLimit,
          actualEnergy,
          benchmarkTotal,
          overLimit ? null : BigDecimal.ZERO,
          overLimit ? "额定标杆月总正常上限为 0，实际总电量大于 0" : "额定标杆月总值与实际总电量均为 0");
    }
    return new MetricResult(
        true,
        overLimit,
        actualEnergy,
        benchmarkTotal,
        overLimit ? ratio(actualEnergy, benchmarkTotal) : BigDecimal.ZERO,
        "额定正常上限 = 额定功率标杆月总值；本期实际总电量大于月总标杆则超标");
  }

  private BigDecimal ratio(BigDecimal actual, BigDecimal threshold) {
    if (threshold.signum() == 0) {
      return null;
    }
    return actual
        .subtract(threshold)
        .divide(threshold, INTERNAL_CONTEXT)
        .multiply(BigDecimal.valueOf(100))
        .setScale(6, RoundingMode.HALF_UP);
  }

  private BigDecimal divideFormula(BigDecimal numerator, BigDecimal denominator) {
    return numerator.divide(denominator, FORMULA_SCALE, RoundingMode.HALF_UP);
  }
}
