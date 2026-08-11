package com.threefees.importing.application;

import com.threefees.importing.application.ImportBatchRepository.ImportedRow;
import com.threefees.importing.domain.DatasetType;
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

  public void validate(ImportBatch batch, List<ImportedRow> rows) {
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
      ImportBatch batch, List<ImportedRow> rows, List<ImportError> errors) {
    validateBillingPointReferences(batch, rows, errors);
    Set<String> incomingPairs = new HashSet<>();
    for (ImportedRow row : rows) {
      incomingPairs.add(pair(row.billingPointCode(), row.paymentCode()));
    }
    for (ReferenceRow meter : activeReferences(batch, DatasetType.METER_READING)) {
      if (!incomingPairs.contains(pair(meter.billingPointCode(), meter.paymentCode()))) {
        add(
            errors,
            0,
            "缴费单编码",
            "ACTIVE_METER_PAYMENT_MISSING",
            "当前有效电表读数仍引用将被移除的缴费单：" + meter.paymentCode());
      }
    }
  }

  private void validateMeters(ImportBatch batch, List<ImportedRow> rows, List<ImportError> errors) {
    Set<String> activePayments = new HashSet<>();
    for (ReferenceRow payment : activeReferences(batch, DatasetType.PAYMENT)) {
      activePayments.add(pair(payment.billingPointCode(), payment.paymentCode()));
    }
    for (ImportedRow row : rows) {
      if (!activePayments.contains(pair(row.billingPointCode(), row.paymentCode()))) {
        add(
            errors,
            row.sourceRow(),
            "缴费单编码",
            "PAYMENT_REFERENCE_NOT_FOUND",
            "同城市同账期不存在对应报账点的当前有效缴费单");
      }
    }
  }

  private void validateBillingPointReferences(
      ImportBatch batch, List<ImportedRow> rows, List<ImportError> errors) {
    Set<String> activePoints = activeBillingPointCodes(batch);
    for (ImportedRow row : rows) {
      if (!activePoints.contains(row.billingPointCode())) {
        add(
            errors,
            row.sourceRow(),
            "报账点编码",
            "BILLING_POINT_REFERENCE_NOT_FOUND",
            "同城市同账期不存在当前有效报账点清单记录");
      }
    }
  }

  private void validateBillingPointReplacement(
      ImportBatch batch, List<ImportedRow> rows, List<ImportError> errors) {
    Set<String> incoming = new HashSet<>();
    rows.forEach(row -> incoming.add(row.billingPointCode()));
    for (DatasetType dependent :
        List.of(DatasetType.PAYMENT, DatasetType.METER_READING, DatasetType.BENCHMARK)) {
      for (ReferenceRow reference : activeReferences(batch, dependent)) {
        if (!incoming.contains(reference.billingPointCode())) {
          add(
              errors,
              0,
              "报账点编码",
              "ACTIVE_DEPENDENT_BILLING_POINT_MISSING",
              dependent + " 当前有效批次仍引用将被移除的报账点：" + reference.billingPointCode());
        }
      }
    }
  }

  private Set<String> activeBillingPointCodes(ImportBatch batch) {
    return new HashSet<>(
        jdbcTemplate.queryForList(
            """
            SELECT r.billing_point_code
              FROM imported_record r
              JOIN import_batch b ON b.id=r.batch_id AND b.status='ACTIVE'
             WHERE r.dataset_type='BILLING_POINT' AND r.data_period=? AND r.city_code=?
               AND r.is_active=TRUE
            """,
            String.class,
            batch.period(),
            batch.cityCode()));
  }

  private List<ReferenceRow> activeReferences(ImportBatch batch, DatasetType type) {
    return jdbcTemplate.query(
        """
        SELECT r.billing_point_code, r.payment_code
          FROM imported_record r
          JOIN import_batch b ON b.id=r.batch_id AND b.status='ACTIVE'
         WHERE r.dataset_type=? AND r.data_period=? AND r.city_code=? AND r.is_active=TRUE
        """,
        (resultSet, rowNumber) ->
            new ReferenceRow(
                resultSet.getString("billing_point_code"), resultSet.getString("payment_code")),
        type.name(),
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
