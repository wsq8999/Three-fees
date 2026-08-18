package com.threefees.audit.application;

import com.threefees.audit.domain.AuditCalculationInput;
import com.threefees.audit.domain.AuditCalculationResult;
import com.threefees.audit.domain.AuditCalculator;
import com.threefees.audit.domain.MetricResult;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuditRecalculationService {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final AuditCalculator calculator;

  public AuditRecalculationService(
      JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, AuditCalculator calculator) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.calculator = calculator;
  }

  @Transactional
  public void recalculate(String period, String cityCode) {
    jdbcTemplate.update(
        """
        DELETE FROM audit_result
         WHERE data_period = ? AND city_code = ?
           AND billing_point_code NOT IN (
             SELECT s.billing_point_code
               FROM billing_point_snapshot s
              WHERE s.data_period = ? AND s.city_code = ?
           )
        """,
        period,
        cityCode,
        period,
        cityCode);
    List<String> billingPointCodes =
        jdbcTemplate.queryForList(
            """
            SELECT s.billing_point_code
              FROM billing_point_snapshot s
             WHERE s.data_period = ? AND s.city_code = ?
             ORDER BY s.billing_point_code
            """,
            String.class,
            period,
            cityCode);
    for (String billingPointCode : billingPointCodes) {
      recalculateOne(YearMonth.parse(period), cityCode, billingPointCode);
    }
  }

  @Transactional
  public void recalculate(String period, String cityCode, Collection<String> billingPointCodes) {
    var affectedCodes = new LinkedHashSet<String>();
    for (String billingPointCode : billingPointCodes) {
      if (billingPointCode != null && !billingPointCode.isBlank()) {
        affectedCodes.add(billingPointCode);
      }
    }
    if (affectedCodes.isEmpty()) {
      return;
    }
    String placeholders =
        String.join(",", java.util.Collections.nCopies(affectedCodes.size(), "?"));
    var arguments = new java.util.ArrayList<Object>();
    arguments.add(period);
    arguments.add(cityCode);
    arguments.addAll(affectedCodes);
    List<String> existingCodes =
        jdbcTemplate.queryForList(
            """
            SELECT s.billing_point_code
              FROM billing_point_snapshot s
             WHERE s.data_period = ? AND s.city_code = ? AND s.billing_point_code IN (
            """
                + placeholders
                + ") ORDER BY s.billing_point_code",
            String.class,
            arguments.toArray());
    for (String billingPointCode : existingCodes) {
      recalculateOne(YearMonth.parse(period), cityCode, billingPointCode);
    }
  }

  private void recalculateOne(YearMonth period, String cityCode, String billingPointCode) {
    SnapshotInfo snapshot = loadSnapshot(period, cityCode, billingPointCode);
    EnergyAndPayment current = loadActual(period, cityCode, billingPointCode);
    BigDecimal benchmarkTotal = loadBenchmarkTotal(period, cityCode, billingPointCode);
    AuditCalculationInput.ReferencePeriod yoy =
        reference(period.minusYears(1), cityCode, billingPointCode);
    AuditCalculationInput.ReferencePeriod mom =
        previousApprovedPaymentReference(period, cityCode, billingPointCode);
    AuditCalculationResult result =
        calculator.calculate(
            new AuditCalculationInput(
                period,
                current.paymentEligible(),
                current.actualEnergy(),
                current.paymentDaysOr(period.lengthOfMonth()),
                benchmarkTotal,
                yoy,
                mom));
    AuditEvidence evidence =
        new AuditEvidence(
            "THREE_FEES_AUDIT_V1",
            period,
            current.paymentEligible(),
            current.actualEnergy(),
            current.actualAmount(),
            current.paymentDaysOr(period.lengthOfMonth()),
            benchmarkTotal,
            yoy,
            mom,
            result);
    upsert(period, cityCode, snapshot, current, evidence);
  }

  private SnapshotInfo loadSnapshot(YearMonth period, String cityCode, String billingPointCode) {
    return jdbcTemplate.queryForObject(
        """
        SELECT billing_point_code, billing_point_name, city_code, district_code,
               data_period, period_start, period_end
          FROM billing_point_snapshot
         WHERE data_period = ? AND city_code = ? AND billing_point_code = ?
        """,
        (resultSet, rowNumber) ->
            new SnapshotInfo(
                resultSet.getString("billing_point_code"),
                resultSet.getString("billing_point_name"),
                resultSet.getString("city_code"),
                resultSet.getString("district_code"),
                resultSet.getString("data_period"),
                resultSet.getObject("period_start", LocalDate.class),
                resultSet.getObject("period_end", LocalDate.class)),
        period.toString(),
        cityCode,
        billingPointCode);
  }

  private AuditCalculationInput.ReferencePeriod reference(
      YearMonth period, String cityCode, String billingPointCode) {
    EnergyAndPayment actual = loadActual(period, cityCode, billingPointCode);
    if (!actual.hasPayments() && actual.actualEnergy() == null) {
      return null;
    }
    return new AuditCalculationInput.ReferencePeriod(
        period,
        actual.paymentEligible(),
        actual.actualEnergy(),
        actual.paymentDaysOr(period.lengthOfMonth()),
        loadBenchmarkTotal(period, cityCode, billingPointCode));
  }

  private AuditCalculationInput.ReferencePeriod previousApprovedPaymentReference(
      YearMonth currentPeriod, String cityCode, String billingPointCode) {
    List<String> periods =
        jdbcTemplate.queryForList(
            """
            SELECT DISTINCT data_period
              FROM payment_detail
             WHERE city_code = ? AND billing_point_code = ? AND data_period < ?
             ORDER BY data_period DESC
            """,
            String.class,
            cityCode,
            billingPointCode,
            currentPeriod.toString());
    for (String periodText : periods) {
      YearMonth period = YearMonth.parse(periodText);
      EnergyAndPayment actual = loadActual(period, cityCode, billingPointCode);
      if (actual.paymentEligible()) {
        return new AuditCalculationInput.ReferencePeriod(
            period,
            true,
            actual.actualEnergy(),
            actual.paymentDaysOr(period.lengthOfMonth()),
            loadBenchmarkTotal(period, cityCode, billingPointCode));
      }
    }
    return null;
  }

  private EnergyAndPayment loadActual(YearMonth period, String cityCode, String billingPointCode) {
    BigDecimal actualEnergy =
        jdbcTemplate.queryForObject(
            """
            SELECT COALESCE(SUM(allocated_kwh), 0)
              FROM meter_reading
             WHERE data_period = ? AND city_code = ? AND billing_point_code = ?
            """,
            BigDecimal.class,
            period.toString(),
            cityCode,
            billingPointCode);
    Integer meterCount =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM meter_reading
             WHERE data_period = ? AND city_code = ? AND billing_point_code = ?
            """,
            Integer.class,
            period.toString(),
            cityCode,
            billingPointCode);

    List<PaymentStatusAndAmount> payments =
        jdbcTemplate.query(
            """
            SELECT audit_status, actual_report_amount, payment_start, payment_end
              FROM payment_detail
             WHERE data_period = ? AND city_code = ? AND billing_point_code = ?
            """,
            (resultSet, rowNumber) ->
                new PaymentStatusAndAmount(
                    resultSet.getString("audit_status"),
                    resultSet.getBigDecimal("actual_report_amount"),
                    resultSet.getObject("payment_start", LocalDate.class),
                    resultSet.getObject("payment_end", LocalDate.class)),
            period.toString(),
            cityCode,
            billingPointCode);
    boolean hasPayments = !payments.isEmpty();
    boolean eligible = hasPayments;
    BigDecimal actualAmount = BigDecimal.ZERO;
    LocalDate paymentStart = null;
    LocalDate paymentEnd = null;
    for (PaymentStatusAndAmount payment : payments) {
      eligible &= isApproved(payment.auditStatus());
      if (payment.actualAmount() != null) {
        actualAmount = actualAmount.add(payment.actualAmount());
      }
      if (payment.paymentStart() != null
          && (paymentStart == null || payment.paymentStart().isBefore(paymentStart))) {
        paymentStart = payment.paymentStart();
      }
      if (payment.paymentEnd() != null
          && (paymentEnd == null || payment.paymentEnd().isAfter(paymentEnd))) {
        paymentEnd = payment.paymentEnd();
      }
    }
    return new EnergyAndPayment(
        meterCount == null || meterCount == 0 ? null : actualEnergy,
        hasPayments ? actualAmount : null,
        payments.size(),
        hasPayments,
        eligible,
        paymentDays(paymentStart, paymentEnd));
  }

  private BigDecimal loadBenchmarkTotal(
      YearMonth period, String cityCode, String billingPointCode) {
    return jdbcTemplate
        .query(
            """
            SELECT calculated_day_total
              FROM benchmark_value
             WHERE data_period = ? AND city_code = ? AND billing_point_code = ?
            """,
            (resultSet, rowNumber) -> resultSet.getBigDecimal("calculated_day_total"),
            period.toString(),
            cityCode,
            billingPointCode)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private void upsert(
      YearMonth period,
      String cityCode,
      SnapshotInfo snapshot,
      EnergyAndPayment current,
      AuditEvidence evidence) {
    AuditCalculationResult result = evidence.result();
    String reportStatus =
        result.status().name().equals("OVER_LIMIT")
            ? existingReport(snapshot.billingPointCode(), period, cityCode)
                ? "GENERATED"
                : "WAITING"
            : "NA";
    String paymentEligibilityReason =
        current.hasPayments() ? current.paymentEligible() ? "全部缴费明细审核通过" : "存在未审核通过的缴费明细" : "无缴费明细";
    int updated =
        jdbcTemplate.update(
            """
            UPDATE audit_result
               SET billing_point_name = ?, district_code = ?, period_start = ?, period_end = ?,
                   payment_count = ?, payment_eligible = ?, payment_eligibility_reason = ?,
                   actual_report_amount = ?, actual_total_kwh = ?, current_daily_avg_kwh = ?,
                   yoy_applicable = ?, yoy_na_reason = ?, yoy_reference_period = ?,
                   yoy_reference_start = ?, yoy_reference_end = ?, yoy_reference_total_kwh = ?,
                   yoy_reference_daily_kwh_c = ?, yoy_current_benchmark_avg_a = ?,
                   yoy_reference_benchmark_avg_b = ?, yoy_factor_k = ?,
                   yoy_threshold_daily_kwh = ?, yoy_exceed_ratio = ?, yoy_result = ?,
                   mom_applicable = ?, mom_na_reason = ?, mom_reference_period = ?,
                   mom_reference_start = ?, mom_reference_end = ?, mom_reference_total_kwh = ?,
                   mom_reference_daily_kwh_c = ?, mom_current_benchmark_avg_a = ?,
                   mom_reference_benchmark_avg_b = ?, mom_factor_k = ?,
                   mom_threshold_daily_kwh = ?, mom_exceed_ratio = ?, mom_result = ?,
                   rated_applicable = ?, rated_na_reason = ?, rated_total_kwh = ?,
                   rated_month_avg_kwh = ?, rated_exceed_ratio = ?, rated_result = ?,
                   audit_status = ?, exceed_type = ?, max_exceed_ratio = ?, report_status = ?,
                   calculation_detail = ?,
                   actual_energy = ?, actual_amount = ?,
                   yoy_reference_energy = ?, mom_reference_energy = ?,
                   rated_benchmark_energy = ?, yoy_ratio = ?, mom_ratio = ?, rated_ratio = ?,
                   max_ratio = ?, over_limit_type = ?, detail_json = ?,
                   calculated_at = CURRENT_TIMESTAMP(3), version = version + 1
             WHERE billing_point_code = ? AND data_period = ? AND city_code = ?
            """,
            snapshot.billingPointName(),
            snapshot.districtCode(),
            snapshot.periodStart(),
            snapshot.periodEnd(),
            current.paymentCount(),
            current.paymentEligible(),
            paymentEligibilityReason,
            current.actualAmount(),
            result.actualEnergy(),
            result.currentDailyEnergy(),
            result.yoy().applicable(),
            notApplicableReason(result.yoy()),
            referencePeriod(evidence.yoyReference()),
            referenceStart(evidence.yoyReference()),
            referenceEnd(evidence.yoyReference()),
            referenceEnergy(evidence.yoyReference()),
            referenceDaily(evidence.yoyReference()),
            benchmarkAverage(evidence.currentBenchmarkTotal(), evidence.currentPaymentDays()),
            referenceBenchmarkAverage(evidence.yoyReference()),
            factorK(
                evidence.currentBenchmarkTotal(),
                evidence.currentPaymentDays(),
                evidence.yoyReference()),
            result.yoy().threshold(),
            result.yoy().ratioPercent(),
            metricStatus(result.yoy()),
            result.mom().applicable(),
            notApplicableReason(result.mom()),
            referencePeriod(evidence.momReference()),
            referenceStart(evidence.momReference()),
            referenceEnd(evidence.momReference()),
            referenceEnergy(evidence.momReference()),
            referenceDaily(evidence.momReference()),
            benchmarkAverage(evidence.currentBenchmarkTotal(), evidence.currentPaymentDays()),
            referenceBenchmarkAverage(evidence.momReference()),
            factorK(
                evidence.currentBenchmarkTotal(),
                evidence.currentPaymentDays(),
                evidence.momReference()),
            result.mom().threshold(),
            result.mom().ratioPercent(),
            metricStatus(result.mom()),
            result.rated().applicable(),
            notApplicableReason(result.rated()),
            evidence.currentBenchmarkTotal(),
            benchmarkAverage(evidence.currentBenchmarkTotal(), evidence.currentPaymentDays()),
            result.rated().ratioPercent(),
            metricStatus(result.rated()),
            result.status().name(),
            result.overLimitType().name(),
            result.maxRatioPercent(),
            reportStatus,
            writeJson(evidence),
            result.actualEnergy(),
            current.actualAmount(),
            referenceEnergy(evidence.yoyReference()),
            referenceEnergy(evidence.momReference()),
            evidence.currentBenchmarkTotal(),
            result.yoy().ratioPercent(),
            result.mom().ratioPercent(),
            result.rated().ratioPercent(),
            result.maxRatioPercent(),
            result.overLimitType().name(),
            writeJson(evidence),
            snapshot.billingPointCode(),
            period.toString(),
            cityCode);
    if (updated == 0) {
      jdbcTemplate.update(
          """
          INSERT INTO audit_result
            (public_id, billing_point_code, billing_point_name, city_code, district_code,
             data_period, period_start, period_end,
             payment_count, payment_eligible, payment_eligibility_reason,
             actual_report_amount, actual_total_kwh, current_daily_avg_kwh,
             yoy_applicable, yoy_na_reason, yoy_reference_period, yoy_reference_start,
             yoy_reference_end, yoy_reference_total_kwh, yoy_reference_daily_kwh_c,
             yoy_current_benchmark_avg_a, yoy_reference_benchmark_avg_b, yoy_factor_k,
             yoy_threshold_daily_kwh, yoy_exceed_ratio, yoy_result,
             mom_applicable, mom_na_reason, mom_reference_period, mom_reference_start,
             mom_reference_end, mom_reference_total_kwh, mom_reference_daily_kwh_c,
             mom_current_benchmark_avg_a, mom_reference_benchmark_avg_b, mom_factor_k,
             mom_threshold_daily_kwh, mom_exceed_ratio, mom_result,
             rated_applicable, rated_na_reason, rated_total_kwh, rated_month_avg_kwh,
             rated_exceed_ratio, rated_result,
             audit_status, exceed_type, max_exceed_ratio, report_status, calculation_detail,
             actual_energy, actual_amount, yoy_reference_energy, mom_reference_energy,
             rated_benchmark_energy, yoy_ratio, mom_ratio, rated_ratio, max_ratio,
             over_limit_type, detail_json)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          UUID.randomUUID().toString(),
          snapshot.billingPointCode(),
          snapshot.billingPointName(),
          cityCode,
          snapshot.districtCode(),
          period.toString(),
          snapshot.periodStart(),
          snapshot.periodEnd(),
          current.paymentCount(),
          current.paymentEligible(),
          paymentEligibilityReason,
          current.actualAmount(),
          result.actualEnergy(),
          result.currentDailyEnergy(),
          result.yoy().applicable(),
          notApplicableReason(result.yoy()),
          referencePeriod(evidence.yoyReference()),
          referenceStart(evidence.yoyReference()),
          referenceEnd(evidence.yoyReference()),
          referenceEnergy(evidence.yoyReference()),
          referenceDaily(evidence.yoyReference()),
          benchmarkAverage(evidence.currentBenchmarkTotal(), evidence.currentPaymentDays()),
          referenceBenchmarkAverage(evidence.yoyReference()),
          factorK(
              evidence.currentBenchmarkTotal(),
              evidence.currentPaymentDays(),
              evidence.yoyReference()),
          result.yoy().threshold(),
          result.yoy().ratioPercent(),
          metricStatus(result.yoy()),
          result.mom().applicable(),
          notApplicableReason(result.mom()),
          referencePeriod(evidence.momReference()),
          referenceStart(evidence.momReference()),
          referenceEnd(evidence.momReference()),
          referenceEnergy(evidence.momReference()),
          referenceDaily(evidence.momReference()),
          benchmarkAverage(evidence.currentBenchmarkTotal(), evidence.currentPaymentDays()),
          referenceBenchmarkAverage(evidence.momReference()),
          factorK(
              evidence.currentBenchmarkTotal(),
              evidence.currentPaymentDays(),
              evidence.momReference()),
          result.mom().threshold(),
          result.mom().ratioPercent(),
          metricStatus(result.mom()),
          result.rated().applicable(),
          notApplicableReason(result.rated()),
          evidence.currentBenchmarkTotal(),
          benchmarkAverage(evidence.currentBenchmarkTotal(), evidence.currentPaymentDays()),
          result.rated().ratioPercent(),
          metricStatus(result.rated()),
          result.status().name(),
          result.overLimitType().name(),
          result.maxRatioPercent(),
          reportStatus,
          writeJson(evidence),
          result.actualEnergy(),
          current.actualAmount(),
          referenceEnergy(evidence.yoyReference()),
          referenceEnergy(evidence.momReference()),
          evidence.currentBenchmarkTotal(),
          result.yoy().ratioPercent(),
          result.mom().ratioPercent(),
          result.rated().ratioPercent(),
          result.maxRatioPercent(),
          result.overLimitType().name(),
          writeJson(evidence));
    }
  }

  private BigDecimal referenceEnergy(AuditCalculationInput.ReferencePeriod reference) {
    return reference == null ? null : reference.actualEnergy();
  }

  private boolean existingReport(String billingPointCode, YearMonth period, String cityCode) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM audit_report r
              JOIN billing_point_snapshot s ON s.id = r.billing_point_snapshot_id
             WHERE s.billing_point_code = ? AND s.data_period = ? AND s.city_code = ?
            """,
            Integer.class,
            billingPointCode,
            period.toString(),
            cityCode);
    return count != null && count > 0;
  }

  private String referencePeriod(AuditCalculationInput.ReferencePeriod reference) {
    return reference == null ? null : reference.period().toString();
  }

  private LocalDate referenceStart(AuditCalculationInput.ReferencePeriod reference) {
    return reference == null ? null : reference.period().atDay(1);
  }

  private LocalDate referenceEnd(AuditCalculationInput.ReferencePeriod reference) {
    return reference == null ? null : reference.period().atEndOfMonth();
  }

  private BigDecimal referenceDaily(AuditCalculationInput.ReferencePeriod reference) {
    return reference == null
        ? null
        : divide(reference.actualEnergy(), BigDecimal.valueOf(reference.paymentDays()));
  }

  private BigDecimal benchmarkAverage(BigDecimal benchmarkTotal, int paymentDays) {
    return divide(benchmarkTotal, BigDecimal.valueOf(paymentDays));
  }

  private BigDecimal referenceBenchmarkAverage(AuditCalculationInput.ReferencePeriod reference) {
    return reference == null
        ? null
        : divide(reference.benchmarkTotal(), BigDecimal.valueOf(reference.paymentDays()));
  }

  private BigDecimal factorK(
      BigDecimal currentBenchmarkTotal,
      int currentPaymentDays,
      AuditCalculationInput.ReferencePeriod reference) {
    BigDecimal currentAverage = benchmarkAverage(currentBenchmarkTotal, currentPaymentDays);
    BigDecimal referenceAverage = referenceBenchmarkAverage(reference);
    if (currentAverage == null || referenceAverage == null || referenceAverage.signum() <= 0) {
      return null;
    }
    return currentAverage.divide(referenceAverage, MathContext.DECIMAL128).max(BigDecimal.ONE);
  }

  private Integer paymentDays(LocalDate start, LocalDate end) {
    if (start == null || end == null || start.isAfter(end)) {
      return null;
    }
    return Math.toIntExact(ChronoUnit.DAYS.between(start, end) + 1);
  }

  private BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
    return numerator == null || denominator == null
        ? null
        : numerator.divide(denominator, MathContext.DECIMAL128);
  }

  private String metricStatus(MetricResult metric) {
    if (!metric.applicable()) {
      return "NA";
    }
    return metric.overLimit() ? "OVER_LIMIT" : "NORMAL";
  }

  private String notApplicableReason(MetricResult metric) {
    return metric.applicable() ? null : metric.note();
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Audit result could not be serialized", exception);
    }
  }

  private boolean isApproved(String status) {
    if (status == null) {
      return false;
    }
    String normalized = status.trim();
    String upper = normalized.toUpperCase(java.util.Locale.ROOT);
    boolean approved = normalized.contains("通过") || upper.equals("APPROVED");
    boolean rejected =
        normalized.contains("未")
            || normalized.contains("不通过")
            || normalized.contains("驳回")
            || normalized.contains("退回")
            || upper.equals("REJECTED");
    return approved && !rejected;
  }

  private record EnergyAndPayment(
      BigDecimal actualEnergy,
      BigDecimal actualAmount,
      int paymentCount,
      boolean hasPayments,
      boolean paymentEligible,
      Integer paymentDays) {

    int paymentDaysOr(int fallback) {
      return paymentDays == null || paymentDays <= 0 ? fallback : paymentDays;
    }
  }

  private record PaymentStatusAndAmount(
      String auditStatus, BigDecimal actualAmount, LocalDate paymentStart, LocalDate paymentEnd) {}

  private record SnapshotInfo(
      String billingPointCode,
      String billingPointName,
      String cityCode,
      String districtCode,
      String dataPeriod,
      LocalDate periodStart,
      LocalDate periodEnd) {}

  /** Immutable evidence envelope persisted with each calculation for report snapshots and audit. */
  public record AuditEvidence(
      String ruleVersion,
      YearMonth currentPeriod,
      boolean currentPaymentEligible,
      BigDecimal currentActualEnergy,
      BigDecimal currentActualAmount,
      int currentPaymentDays,
      BigDecimal currentBenchmarkTotal,
      AuditCalculationInput.ReferencePeriod yoyReference,
      AuditCalculationInput.ReferencePeriod momReference,
      AuditCalculationResult result) {}
}
