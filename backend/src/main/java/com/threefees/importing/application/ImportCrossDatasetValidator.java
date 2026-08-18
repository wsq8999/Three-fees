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
        // 报账点清单本身就是主数据，不需要依赖其他三类数据。
      }

      case PAYMENT, METER_READING, BENCHMARK -> validateBillingPointReferences(batch, rows, errors);
    }

    if (!errors.isEmpty()) {
      throw new ImportValidationException(errors);
    }
  }

  /**
   * 缴费明细、电表读数、标杆值三类数据都只要求：
   *
   * <p>对应报账点已经存在于报账点清单主数据中。
   *
   * <p>后三类数据彼此之间不建立导入先后依赖，因此：
   *
   * <p>清单 -> 缴费明细 / 电表读数 / 标杆值，三者可任意顺序导入。
   */
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

  private void add(List<ImportError> errors, int row, String column, String code, String message) {

    if (errors.size() < MAX_ERRORS) {
      errors.add(new ImportError(row, column, code, message));
    }
  }
}
