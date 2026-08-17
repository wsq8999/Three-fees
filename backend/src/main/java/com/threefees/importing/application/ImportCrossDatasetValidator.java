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
      case BILLING_POINT -> {
        // Billing point files are incremental upserts for their own city + period + point codes.
      }
      case PAYMENT -> validateBillingPointReferences(batch, rows, errors);
      case METER_READING -> validateMeters(batch, rows, errors);
      case BENCHMARK -> validateBillingPointReferences(batch, rows, errors);
    }
    if (!errors.isEmpty()) {
      throw new ImportValidationException(errors);
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
            "\u7f34\u8d39\u5355\u7f16\u7801",
            "PAYMENT_REFERENCE_NOT_FOUND",
            "\u540c\u57ce\u5e02\u540c\u8d26\u671f\u4e0d\u5b58\u5728\u5bf9\u5e94"
                + "\u62a5\u8d26\u70b9\u7684\u7f34\u8d39\u5355");
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
            "\u62a5\u8d26\u70b9\u7f16\u7801",
            "BILLING_POINT_REFERENCE_NOT_FOUND",
            "\u540c\u57ce\u5e02\u4e0d\u5b58\u5728\u5df2\u5bfc\u5165\u7684\u62a5\u8d26\u70b9\u4e3b\u6570\u636e");
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
