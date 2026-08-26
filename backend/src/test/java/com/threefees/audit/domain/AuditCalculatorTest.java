package com.threefees.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class AuditCalculatorTest {

  private final AuditCalculator calculator = new AuditCalculator();

  @Test
  void usesABCAdjustmentAndTwentyPercentMarginForHistoricalMetrics() {
    var current = YearMonth.of(2024, 2);
    var reference =
        new AuditCalculationInput.ReferencePeriod(
            YearMonth.of(2023, 2),
            true,
            new BigDecimal("2800"),
            new BigDecimal("200.00"),
            28,
            new BigDecimal("2800"));

    var result =
        calculator.calculate(
            new AuditCalculationInput(
                current,
                true,
                new BigDecimal("4000"),
                new BigDecimal("137.93"),
                29,
                new BigDecimal("5800"),
                reference,
                reference));

    assertThat(result.yoy().threshold()).isEqualByComparingTo("480");
    assertThat(result.yoy().overLimit()).isFalse();
    assertThat(result.rated().overLimit()).isFalse();
    assertThat(result.status()).isEqualTo(AuditStatus.NORMAL);
  }

  @Test
  void historicalMetricIsNotApplicableWhenBIsZero() {
    var reference =
        new AuditCalculationInput.ReferencePeriod(
            YearMonth.of(2023, 1), true, new BigDecimal("3100"), BigDecimal.ZERO);
    var result =
        calculator.calculate(
            new AuditCalculationInput(
                YearMonth.of(2024, 1),
                true,
                new BigDecimal("3200"),
                new BigDecimal("103.23"),
                31,
                new BigDecimal("3100"),
                reference,
                null));

    assertThat(result.yoy().applicable()).isFalse();
    assertThat(result.rated().overLimit()).isTrue();
    assertThat(result.overLimitType()).isEqualTo(OverLimitType.ONLY_RATED);
  }

  @Test
  void zeroRatedBenchmarkHandlesZeroAndPositiveActualWithoutDivision() {
    var zero =
        calculator.calculate(
            new AuditCalculationInput(
                YearMonth.of(2024, 1), true, BigDecimal.ZERO, BigDecimal.ZERO, null, null));
    var positive =
        calculator.calculate(
            new AuditCalculationInput(
                YearMonth.of(2024, 1), true, BigDecimal.ONE, BigDecimal.ZERO, null, null));

    assertThat(zero.status()).isEqualTo(AuditStatus.NORMAL);
    assertThat(positive.status()).isEqualTo(AuditStatus.OVER_LIMIT);
    assertThat(positive.rated().ratioPercent()).isNull();
    assertThat(positive.rated().note()).contains("正常上限为 0");
  }

  @Test
  void pendingPaymentOnlyBlocksHistoricalMetrics() {
    var result =
        calculator.calculate(
            new AuditCalculationInput(
                YearMonth.of(2024, 1), false, new BigDecimal("100"), BigDecimal.TEN, null, null));

    assertThat(result.yoy().applicable()).isFalse();
    assertThat(result.mom().applicable()).isFalse();
    assertThat(result.rated().overLimit()).isTrue();
    assertThat(result.status()).isEqualTo(AuditStatus.OVER_LIMIT);
  }

  @Test
  void missingActualMakesFinalResultNotApplicable() {
    var result =
        calculator.calculate(
            new AuditCalculationInput(
                YearMonth.of(2024, 1), true, null, BigDecimal.TEN, null, null));

    assertThat(result.status()).isEqualTo(AuditStatus.NOT_APPLICABLE);
    assertThat(result.yoy().applicable()).isFalse();
    assertThat(result.rated().applicable()).isFalse();
  }

  @Test
  void importedDailyValueUsesSixDecimalScaleBeforeThresholdDecision() {
    var reference =
        new AuditCalculationInput.ReferencePeriod(
            YearMonth.of(2023, 1),
            true,
            new BigDecimal("31"),
            new BigDecimal("1.00"),
            31,
            new BigDecimal("31"));
    var result =
        calculator.calculate(
            new AuditCalculationInput(
                YearMonth.of(2024, 1),
                true,
                new BigDecimal("37.200000000000000000000000000000031"),
                new BigDecimal("1.200000000000000000000000000000001"),
                31,
                new BigDecimal("31"),
                reference,
                null));

    assertThat(result.yoy().threshold()).isEqualByComparingTo("1.20");
    assertThat(result.yoy().overLimit()).isFalse();
  }

  @Test
  void historicalDailyMetricsUsePaymentDaysAndDoNotReduceThresholdWhenRatedRatioIsBelowOne() {
    var reference =
        new AuditCalculationInput.ReferencePeriod(
            YearMonth.of(2025, 6),
            true,
            new BigDecimal("300"),
            new BigDecimal("30.00"),
            10,
            new BigDecimal("400"));
    var result =
        calculator.calculate(
            new AuditCalculationInput(
                YearMonth.of(2026, 6),
                true,
                new BigDecimal("361"),
                new BigDecimal("36.10"),
                10,
                new BigDecimal("200"),
                reference,
                reference));

    assertThat(result.currentDailyEnergy()).isEqualByComparingTo("36.1");
    assertThat(result.yoy().threshold()).isEqualByComparingTo("36.00");
    assertThat(result.yoy().overLimit()).isTrue();
    assertThat(result.yoy().note()).contains("max(1, A/B)");
  }

  @Test
  void historicalMetricsUseImportedDailyEnergyWhenAvailableForRatio() {
    var reference =
        new AuditCalculationInput.ReferencePeriod(
            YearMonth.of(2024, 5),
            true,
            new BigDecimal("1089.02"),
            new BigDecimal("35.13"),
            31,
            new BigDecimal("2218.4576"));

    var result =
        calculator.calculate(
            new AuditCalculationInput(
                YearMonth.of(2025, 5),
                true,
                new BigDecimal("1426.08"),
                new BigDecimal("46.00"),
                31,
                new BigDecimal("1353.1376"),
                reference,
                null));

    assertThat(result.currentDailyEnergy()).isEqualByComparingTo("46.000000");
    assertThat(result.yoy().threshold()).isEqualByComparingTo("42.16");
    assertThat(result.yoy().ratioPercent()).isEqualByComparingTo("9.108159");
  }

  @Test
  void confirmedAbcFormulaUsesSixDecimalIntermediateValues() {
    var yoyReference =
        new AuditCalculationInput.ReferencePeriod(
            YearMonth.of(2025, 1),
            true,
            new BigDecimal("5352.83"),
            new BigDecimal("172.671935"),
            31,
            new BigDecimal("7014.24"));
    var momReference =
        new AuditCalculationInput.ReferencePeriod(
            YearMonth.of(2025, 12),
            true,
            new BigDecimal("4329.48"),
            new BigDecimal("139.660645"),
            31,
            new BigDecimal("7239.102"));

    var result =
        calculator.calculate(
            new AuditCalculationInput(
                YearMonth.of(2026, 1),
                true,
                new BigDecimal("6500"),
                new BigDecimal("209.677419"),
                31,
                new BigDecimal("7020.7312"),
                yoyReference,
                momReference));

    assertThat(result.yoy().threshold()).isEqualByComparingTo("207.40");
    assertThat(result.mom().threshold()).isEqualByComparingTo("167.59");
  }

  @Test
  void historicalMetricsDoNotInferDailyEnergyWhenImportedDailyFieldIsMissing() {
    var reference =
        new AuditCalculationInput.ReferencePeriod(
            YearMonth.of(2025, 1), true, new BigDecimal("3100"), 31, new BigDecimal("3100"));

    var result =
        calculator.calculate(
            new AuditCalculationInput(
                YearMonth.of(2026, 1),
                true,
                new BigDecimal("3200"),
                new BigDecimal("103.23"),
                31,
                new BigDecimal("3100"),
                reference,
                null));

    assertThat(result.yoy().applicable()).isFalse();
    assertThat(result.yoy().note()).contains("A、B、C");
  }
}
