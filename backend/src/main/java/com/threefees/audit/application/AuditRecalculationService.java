package com.threefees.audit.application;

import com.threefees.audit.domain.AuditCalculationInput;
import com.threefees.audit.domain.AuditCalculationResult;
import com.threefees.audit.domain.AuditCalculator;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuditRecalculationService {

  private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP =
      new TypeReference<>() {};

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
               JOIN import_batch b ON b.id = s.source_batch_id AND b.status = 'ACTIVE'
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
              JOIN import_batch b ON b.id = s.source_batch_id AND b.status = 'ACTIVE'
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

  private void recalculateOne(YearMonth period, String cityCode, String billingPointCode) {
    EnergyAndPayment current = loadActual(period, cityCode, billingPointCode);
    BigDecimal benchmarkTotal = loadBenchmarkTotal(period, cityCode, billingPointCode);
    AuditCalculationInput.ReferencePeriod yoy =
        reference(period.minusYears(1), cityCode, billingPointCode);
    AuditCalculationInput.ReferencePeriod mom =
        previousEligibleReference(period, cityCode, billingPointCode);
    AuditCalculationResult result =
        calculator.calculate(
            new AuditCalculationInput(
                period,
                current.paymentEligible(),
                current.actualEnergy(),
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
            benchmarkTotal,
            yoy,
            mom,
            result);
    upsert(period, cityCode, billingPointCode, current.actualAmount(), evidence);
  }

  private AuditCalculationInput.ReferencePeriod previousEligibleReference(
      YearMonth current, String cityCode, String billingPointCode) {
    List<String> candidates =
        jdbcTemplate.queryForList(
            """
            SELECT DISTINCT s.data_period
              FROM billing_point_snapshot s
              JOIN import_batch b ON b.id = s.source_batch_id AND b.status = 'ACTIVE'
             WHERE s.city_code = ? AND s.billing_point_code = ? AND s.data_period < ?
             ORDER BY s.data_period DESC
            """,
            String.class,
            cityCode,
            billingPointCode,
            current.toString());
    for (String candidate : candidates) {
      YearMonth month = YearMonth.parse(candidate);
      EnergyAndPayment actual = loadActual(month, cityCode, billingPointCode);
      if (actual.paymentEligible()) {
        return new AuditCalculationInput.ReferencePeriod(
            month,
            true,
            actual.actualEnergy(),
            loadBenchmarkTotal(month, cityCode, billingPointCode));
      }
    }
    return null;
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
        loadBenchmarkTotal(period, cityCode, billingPointCode));
  }

  private EnergyAndPayment loadActual(YearMonth period, String cityCode, String billingPointCode) {
    List<String> meterJson = activeRows("METER_READING", period, cityCode, billingPointCode);
    BigDecimal actualEnergy = null;
    if (!meterJson.isEmpty()) {
      actualEnergy = BigDecimal.ZERO;
      for (String json : meterJson) {
        String raw = readMap(json).getOrDefault("分摊后度数", "");
        if (!raw.isBlank()) {
          actualEnergy = actualEnergy.add(decimal(raw));
        }
      }
    }

    List<String> paymentJson = activeRows("PAYMENT", period, cityCode, billingPointCode);
    boolean eligible = !paymentJson.isEmpty();
    BigDecimal actualAmount = BigDecimal.ZERO;
    for (String json : paymentJson) {
      Map<String, String> values = readMap(json);
      String status = firstNonBlank(values.get("审核结果"), values.get("审核状态"), values.get("当前审核环节"));
      eligible &= isApproved(status);
      String amount = values.getOrDefault("实际报账金额", "");
      if (!amount.isBlank()) {
        actualAmount = actualAmount.add(decimal(amount));
      }
    }
    return new EnergyAndPayment(
        actualEnergy,
        paymentJson.isEmpty() ? null : actualAmount,
        !paymentJson.isEmpty(),
        eligible);
  }

  private BigDecimal loadBenchmarkTotal(
      YearMonth period, String cityCode, String billingPointCode) {
    List<String> records = activeRows("BENCHMARK", period, cityCode, billingPointCode);
    if (records.size() != 1) {
      return null;
    }
    Map<String, String> values = readMap(records.getFirst());
    BigDecimal total = BigDecimal.ZERO;
    for (int day = 1; day <= period.lengthOfMonth(); day++) {
      String raw = values.getOrDefault(Integer.toString(day), "");
      if (raw.isBlank()) {
        return null;
      }
      total = total.add(decimal(raw));
    }
    return total;
  }

  private List<String> activeRows(
      String datasetType, YearMonth period, String cityCode, String billingPointCode) {
    return jdbcTemplate.queryForList(
        """
        SELECT r.values_json
          FROM imported_record r
          JOIN import_batch b ON b.id = r.batch_id AND b.status = 'ACTIVE'
         WHERE r.dataset_type = ? AND r.data_period = ? AND r.city_code = ?
           AND r.billing_point_code = ? AND r.is_active = TRUE
         ORDER BY r.id
        """,
        String.class,
        datasetType,
        period.toString(),
        cityCode,
        billingPointCode);
  }

  private void upsert(
      YearMonth period,
      String cityCode,
      String billingPointCode,
      BigDecimal actualAmount,
      AuditEvidence evidence) {
    AuditCalculationResult result = evidence.result();
    int updated =
        jdbcTemplate.update(
            """
            UPDATE audit_result
               SET payment_eligible = ?, actual_energy = ?, actual_amount = ?,
                   yoy_reference_energy = ?, mom_reference_energy = ?,
                   rated_benchmark_energy = ?, yoy_ratio = ?, mom_ratio = ?, rated_ratio = ?,
                   max_ratio = ?, audit_status = ?, over_limit_type = ?, detail_json = ?,
                   calculated_at = CURRENT_TIMESTAMP(3), version = version + 1
             WHERE billing_point_code = ? AND data_period = ?
            """,
            evidence.currentPaymentEligible(),
            result.actualEnergy(),
            actualAmount,
            referenceEnergy(evidence.yoyReference()),
            referenceEnergy(evidence.momReference()),
            evidence.currentBenchmarkTotal(),
            result.yoy().ratioPercent(),
            result.mom().ratioPercent(),
            result.rated().ratioPercent(),
            result.maxRatioPercent(),
            result.status().name(),
            result.overLimitType().name(),
            writeJson(evidence),
            billingPointCode,
            period.toString());
    if (updated == 0) {
      jdbcTemplate.update(
          """
          INSERT INTO audit_result
            (public_id, billing_point_code, city_code, data_period, payment_eligible,
             actual_energy, actual_amount, yoy_reference_energy, mom_reference_energy,
             rated_benchmark_energy, yoy_ratio, mom_ratio, rated_ratio, max_ratio,
             audit_status, over_limit_type, detail_json)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          UUID.randomUUID().toString(),
          billingPointCode,
          cityCode,
          period.toString(),
          evidence.currentPaymentEligible(),
          result.actualEnergy(),
          actualAmount,
          referenceEnergy(evidence.yoyReference()),
          referenceEnergy(evidence.momReference()),
          evidence.currentBenchmarkTotal(),
          result.yoy().ratioPercent(),
          result.mom().ratioPercent(),
          result.rated().ratioPercent(),
          result.maxRatioPercent(),
          result.status().name(),
          result.overLimitType().name(),
          writeJson(evidence));
    }
  }

  private BigDecimal referenceEnergy(AuditCalculationInput.ReferencePeriod reference) {
    return reference == null ? null : reference.actualEnergy();
  }

  private Map<String, String> readMap(String json) {
    try {
      return objectMapper.readValue(json, STRING_MAP);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted imported row is invalid JSON", exception);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Audit result could not be serialized", exception);
    }
  }

  private BigDecimal decimal(String value) {
    return new BigDecimal(value.replace(",", ""));
  }

  private boolean isApproved(String status) {
    if (status == null) {
      return false;
    }
    String normalized = status.trim();
    return (normalized.contains("通过") || normalized.equalsIgnoreCase("APPROVED"))
        && !normalized.contains("未")
        && !normalized.contains("不通过");
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private record EnergyAndPayment(
      BigDecimal actualEnergy,
      BigDecimal actualAmount,
      boolean hasPayments,
      boolean paymentEligible) {}

  /** Immutable evidence envelope persisted with each calculation for report snapshots and audit. */
  public record AuditEvidence(
      String ruleVersion,
      YearMonth currentPeriod,
      boolean currentPaymentEligible,
      BigDecimal currentActualEnergy,
      BigDecimal currentActualAmount,
      BigDecimal currentBenchmarkTotal,
      AuditCalculationInput.ReferencePeriod yoyReference,
      AuditCalculationInput.ReferencePeriod momReference,
      AuditCalculationResult result) {}
}
