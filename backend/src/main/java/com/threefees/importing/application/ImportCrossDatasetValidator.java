package com.threefees.importing.application;

import com.threefees.importing.domain.ImportBatch;
import com.threefees.importing.domain.ImportError;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ImportCrossDatasetValidator {

  private static final int MAX_ERRORS = 1000;

  private final JdbcTemplate jdbcTemplate;

  public ImportCrossDatasetValidator(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void validate(ImportBatch batch, List<ImportRow> rows) {
    var errors = new ArrayList<ImportError>();
    switch (batch.datasetType()) {
      case BILLING_POINT -> validateBillingPointReplacement(batch, rows, errors);
      case PAYMENT -> validatePayments(batch, rows, errors);
      case METER_READING -> validateMeters(batch, rows, errors);
      case BENCHMARK -> validateBillingPointReferences(batch, rows, errors);
    }
    if (!errors.isEmpty()) {
      throw new ImportValidationException(errors);
    }
  }

  private void validatePayments(
      ImportBatch batch, List<ImportRow> rows, List<ImportError> errors) {
    validateBillingPointReferences(batch, rows, errors);
    Set<String> incomingPairs = new HashSet<>();
    for (ImportRow row : rows) {
      incomingPairs.add(pair(row.billingPointCode(), row.paymentCode()));
    }
    for (ReferenceRow meter : meterReferences(batch)) {
      if (!incomingPairs.contains(pair(meter.billingPointCode(), meter.paymentCode()))) {
        add(
            errors,
            0,
            "缴费单编码",
            "ACTIVE_METER_PAYMENT_MISSING",
            "当前有效电表读数仍引用将被替换的缴费单：" + meter.paymentCode());
      }
    }
  }

  private void validateMeters(ImportBatch batch, List<ImportRow> rows, List<ImportError> errors) {
    Set<String> activePayments = new HashSet<>();
    for (ReferenceRow payment : paymentReferences(batch)) {
      activePayments.add(pair(payment.billingPointCode(), payment.paymentCode()));
    }
    for (ImportRow row : rows) {
      if (!activePayments.contains(pair(row.billingPointCode(), row.paymentCode()))) {
        add(
            errors,
            row.sourceRow(),
            "缴费单编码",
            "PAYMENT_REFERENCE_NOT_FOUND",
            "同城市同账期不存在对应报账点的缴费单");
      }
    }
  }

  private void validateBillingPointReferences(
      ImportBatch batch, List<ImportRow> rows, List<ImportError> errors) {
    Set<String> knownPoints = knownBillingPointCodes(batch);
    for (ImportRow row : rows) {
      if (!knownPoints.contains(row.billingPointCode())) {
        add(
            errors,
            row.sourceRow(),
            "报账点编码",
            "BILLING_POINT_REFERENCE_NOT_FOUND",
            "同城市不存在已导入的报账点主数据");
      }
    }
  }

  private void validateBillingPointReplacement(
      ImportBatch batch, List<ImportRow> rows, List<ImportError> errors) {
    Set<String> incoming = new HashSet<>();
    rows.forEach(row -> incoming.add(row.billingPointCode()));
    for (ReferenceRow reference : paymentReferences(batch)) {
      if (!incoming.contains(reference.billingPointCode())) {
        add(
            errors,
            0,
            "报账点编码",
            "ACTIVE_DEPENDENT_BILLING_POINT_MISSING",
            "当前有效缴费明细仍引用将被移除的报账点：" + reference.billingPointCode());
      }
    }
    for (ReferenceRow reference : meterReferences(batch)) {
      if (!incoming.contains(reference.billingPointCode())) {
        add(
            errors,
            0,
            "报账点编码",
            "ACTIVE_DEPENDENT_BILLING_POINT_MISSING",
            "当前有效电表读数仍引用将被移除的报账点：" + reference.billingPointCode());
      }
    }
    for (ReferenceRow reference : benchmarkReferences(batch)) {
      if (!incoming.contains(reference.billingPointCode())) {
        add(
            errors,
            0,
            "报账点编码",
            "ACTIVE_DEPENDENT_BILLING_POINT_MISSING",
            "当前有效标杆值仍引用将被移除的报账点：" + reference.billingPointCode());
      }
    }
  }

  private Set<String> knownBillingPointCodes(ImportBatch batch) {
    return new HashSet<>(
        jdbcTemplate.queryForList(
            """
            SELECT billing_point_code
              FROM billing_point_master
             WHERE city_code = ?
            UNION
            SELECT billing_point_code
              FROM billing_point_snapshot
             WHERE city_code = ?
            """,
            String.class,
            batch.cityCode(),
            batch.cityCode()));
  }

  private List<ReferenceRow> paymentReferences(ImportBatch batch) {
    return jdbcTemplate.query(
        """
        SELECT billing_point_code, payment_bill_code
          FROM payment_detail
         WHERE data_period = ? AND city_code = ?
        """,
        (resultSet, rowNumber) ->
            new ReferenceRow(
                resultSet.getString("billing_point_code"),
                resultSet.getString("payment_bill_code")),
        batch.period(),
        batch.cityCode());
  }

  private List<ReferenceRow> meterReferences(ImportBatch batch) {
    return jdbcTemplate.query(
        """
        SELECT billing_point_code, payment_bill_code
          FROM meter_reading
         WHERE data_period = ? AND city_code = ?
        """,
        (resultSet, rowNumber) ->
            new ReferenceRow(
                resultSet.getString("billing_point_code"),
                resultSet.getString("payment_bill_code")),
        batch.period(),
        batch.cityCode());
  }

  private List<ReferenceRow> benchmarkReferences(ImportBatch batch) {
    return jdbcTemplate.query(
        """
        SELECT billing_point_code, NULL AS payment_bill_code
          FROM benchmark_value
         WHERE data_period = ? AND city_code = ?
        """,
        (resultSet, rowNumber) -> new ReferenceRow(resultSet.getString("billing_point_code"), null),
        batch.period(),
        batch.cityCode());
  }

  private String pair(String billingPointCode, String paymentCode) {
    return billingPointCode + "\u0000" + (paymentCode == null ? "" : paymentCode);
  }

  private void add(List<ImportError> errors, int row, String column, String code, String message) {
    if (errors.size() < MAX_ERRORS) {
      errors.add(new ImportError(row, column, code, message));
    }
  }

  private record ReferenceRow(String billingPointCode, String paymentCode) {}
}


