package com.threefees.importing.application;

import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ImportBatch;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class FormalImportTableWriter {

  private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP =
      new TypeReference<>() {};

  private static final String CITY = "所属地市";
  private static final String BENCHMARK_CITY = "地市";
  private static final String DISTRICT = "所属区县";
  private static final String BENCHMARK_DISTRICT = "区县";
  private static final String AUDIT_STATUS = "审核状态";
  private static final String BILLING_POINT_TYPE = "报账点类型";
  private static final String BILLING_POINT_STATUS = "报账点状态";
  private static final String LAST_PERIOD_START = "最后报账期始";
  private static final String LAST_PERIOD_END = "最后报账期止";
  private static final String PERIOD_START = "缴费期始";
  private static final String PERIOD_END = "缴费期终";
  private static final String ACTUAL_AMOUNT = "实际报账金额";
  private static final String METER_ACCOUNT_NO = "电表户号";
  private static final String METER_MULTIPLIER = "电表倍率";
  private static final String ALLOCATED_ENERGY = "分摊后度数";
  private static final String YEAR = "年份";
  private static final String MONTH = "月份";
  private static final String MONTHLY_BENCHMARK = "月总标杆";
  private static final String MONTHLY_AVERAGE_BENCHMARK = "月平均标杆";

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public FormalImportTableWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public void replace(ImportBatch batch, List<ImportRow> rows) {
    switch (batch.datasetType()) {
      case BILLING_POINT -> replaceBillingPoints(batch, rows);
      case PAYMENT -> replacePayments(batch, rows);
      case METER_READING -> replaceMeters(batch, rows);
      case BENCHMARK -> replaceBenchmarks(batch, rows);
    }
  }

  private void replaceBillingPoints(ImportBatch batch, List<ImportRow> rows) {
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO billing_point_snapshot
          (public_id, data_period, period_start, period_end, city_code, district_code,
           source_import_job_id, source_row_no, raw_row_json, source_audit_status,
           billing_point_code, billing_point_name, billing_point_type, city_name, district_name,
           billing_point_status, last_reimbursement_start, last_reimbursement_end, data_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          public_id = VALUES(public_id),
          data_period = VALUES(data_period),
          city_code = VALUES(city_code),
          district_code = VALUES(district_code),
          source_import_job_id = VALUES(source_import_job_id),
          source_row_no = VALUES(source_row_no),
          raw_row_json = VALUES(raw_row_json),
          source_audit_status = VALUES(source_audit_status),
          billing_point_name = VALUES(billing_point_name),
          billing_point_type = VALUES(billing_point_type),
          city_name = VALUES(city_name),
          district_name = VALUES(district_name),
          billing_point_status = VALUES(billing_point_status),
          last_reimbursement_start = VALUES(last_reimbursement_start),
          last_reimbursement_end = VALUES(last_reimbursement_end),
          data_json = VALUES(data_json)
        """,
        new RowSetter(rows) {
          @Override
          protected void setRow(PreparedStatement ps, ImportRow row, Map<String, String> values)
              throws SQLException {
            LocalDate start = valueDate(values, LAST_PERIOD_START);
            LocalDate end = valueDate(values, LAST_PERIOD_END);
            if (start == null) {
              start = YearMonth.parse(batch.period()).atDay(1);
            }
            if (end == null) {
              end = YearMonth.parse(batch.period()).atEndOfMonth();
            }
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, batch.period());
            setDate(ps, 3, start);
            setDate(ps, 4, end);
            ps.setString(5, batch.cityCode());
            ps.setString(6, value(values, DISTRICT));
            ps.setLong(7, batch.id());
            ps.setInt(8, row.sourceRow());
            ps.setString(9, row.valuesJson());
            ps.setString(10, value(values, AUDIT_STATUS));
            ps.setString(11, row.billingPointCode());
            ps.setString(12, row.billingPointName());
            ps.setString(13, value(values, BILLING_POINT_TYPE));
            ps.setString(14, valueOr(values, CITY, batch.cityCode()));
            ps.setString(15, value(values, DISTRICT));
            ps.setString(16, value(values, BILLING_POINT_STATUS));
            setDate(ps, 17, valueDate(values, LAST_PERIOD_START));
            setDate(ps, 18, valueDate(values, LAST_PERIOD_END));
            ps.setString(19, row.valuesJson());
          }
        });
    syncBillingPointMaster(batch, rows);
  }

  private void syncBillingPointMaster(ImportBatch batch, List<ImportRow> rows) {
    List<String> billingPointCodes = billingPointCodes(rows);
    if (billingPointCodes.isEmpty()) {
      return;
    }
    String placeholders = placeholders(billingPointCodes.size());
    jdbcTemplate.update(
        "DELETE FROM billing_point_master WHERE billing_point_code IN (" + placeholders + ")",
        billingPointCodes.toArray());
    var arguments = new java.util.ArrayList<Object>();
    arguments.add(batch.cityCode());
    arguments.add(batch.period());
    arguments.addAll(billingPointCodes);
    jdbcTemplate.update(
        """
        INSERT INTO billing_point_master
          (billing_point_code, billing_point_name, city_code, district_code,
           billing_point_status, current_data_period, current_snapshot_id)
        SELECT s.billing_point_code, s.billing_point_name, s.city_code, s.district_code,
               s.billing_point_status, s.data_period, s.id
          FROM billing_point_snapshot s
         WHERE s.city_code = ? AND s.data_period = ? AND s.billing_point_code IN (
        """
            + placeholders
            + ")",
        arguments.toArray());
  }

  private void replacePayments(ImportBatch batch, List<ImportRow> rows) {
    deleteCurrentImportedBillingPoints("payment_detail", batch, rows);
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO payment_detail
          (public_id, data_period, period_start, period_end, city_code, district_code,
           source_import_job_id, source_row_no, raw_row_json, audit_status, payment_bill_code,
           city_name, district_name, billing_point_code, billing_point_name, payment_start,
           payment_end, actual_report_amount, values_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        new RowSetter(rows) {
          @Override
          protected void setRow(PreparedStatement ps, ImportRow row, Map<String, String> values)
              throws SQLException {
            LocalDate start = requiredDate(values, PERIOD_START, batch.period());
            LocalDate end = valueDate(values, PERIOD_END);
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, batch.period());
            setDate(ps, 3, start);
            setDate(ps, 4, end == null ? start : end);
            ps.setString(5, batch.cityCode());
            ps.setString(6, value(values, DISTRICT));
            ps.setLong(7, batch.id());
            ps.setInt(8, row.sourceRow());
            ps.setString(9, row.valuesJson());
            ps.setString(10, value(values, AUDIT_STATUS));
            ps.setString(11, row.paymentCode());
            ps.setString(12, valueOr(values, CITY, batch.cityCode()));
            ps.setString(13, value(values, DISTRICT));
            ps.setString(14, row.billingPointCode());
            ps.setString(15, row.billingPointName());
            setDate(ps, 16, start);
            setDate(ps, 17, end);
            setBigDecimal(ps, 18, valueDecimal(values, ACTUAL_AMOUNT));
            ps.setString(19, row.valuesJson());
          }
        });
  }

  private void replaceMeters(ImportBatch batch, List<ImportRow> rows) {
    deleteCurrentImportedBillingPoints("meter_reading", batch, rows);
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO meter_reading
          (public_id, data_period, period_start, period_end, city_code, district_code,
           source_import_job_id, source_row_no, raw_row_json, billing_point_name,
           billing_point_code, payment_bill_code, payment_start, payment_end, meter_code,
           meter_account_no, meter_multiplier, allocated_kwh, values_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        new RowSetter(rows) {
          @Override
          protected void setRow(PreparedStatement ps, ImportRow row, Map<String, String> values)
              throws SQLException {
            LocalDate start = requiredDate(values, PERIOD_START, batch.period());
            LocalDate end = valueDate(values, PERIOD_END);
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, batch.period());
            setDate(ps, 3, start);
            setDate(ps, 4, end == null ? start : end);
            ps.setString(5, batch.cityCode());
            ps.setString(6, value(values, DISTRICT));
            ps.setLong(7, batch.id());
            ps.setInt(8, row.sourceRow());
            ps.setString(9, row.valuesJson());
            ps.setString(10, row.billingPointName());
            ps.setString(11, row.billingPointCode());
            ps.setString(12, row.paymentCode());
            setDate(ps, 13, start);
            setDate(ps, 14, end);
            ps.setString(15, row.meterCode());
            ps.setString(16, value(values, METER_ACCOUNT_NO));
            setBigDecimal(ps, 17, valueDecimal(values, METER_MULTIPLIER));
            setBigDecimal(ps, 18, valueDecimal(values, ALLOCATED_ENERGY));
            ps.setString(19, row.valuesJson());
          }
        });
  }

  private void replaceBenchmarks(ImportBatch batch, List<ImportRow> rows) {
    deleteCurrentImportedBillingPoints("benchmark_value", batch, rows);
    jdbcTemplate.batchUpdate(
        benchmarkSql(),
        new RowSetter(rows) {
          @Override
          protected void setRow(PreparedStatement ps, ImportRow row, Map<String, String> values)
              throws SQLException {
            YearMonth month = YearMonth.parse(batch.period());
            BigDecimal dayTotal = BigDecimal.ZERO;
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, batch.period());
            setDate(ps, 3, month.atDay(1));
            setDate(ps, 4, month.atEndOfMonth());
            ps.setString(5, batch.cityCode());
            ps.setString(6, value(values, BENCHMARK_DISTRICT));
            ps.setLong(7, batch.id());
            ps.setInt(8, row.sourceRow());
            ps.setString(9, row.valuesJson());
            ps.setString(10, row.billingPointCode());
            ps.setString(11, row.billingPointName());
            ps.setString(12, value(values, BILLING_POINT_STATUS));
            ps.setString(13, valueOr(values, BENCHMARK_CITY, batch.cityCode()));
            ps.setString(14, value(values, BENCHMARK_DISTRICT));
            ps.setInt(15, intValue(values, YEAR, month.getYear()));
            ps.setInt(16, intValue(values, MONTH, month.getMonthValue()));
            BigDecimal monthBenchmark = firstDecimal(values, MONTHLY_BENCHMARK, MONTHLY_AVERAGE_BENCHMARK);
            setBigDecimal(ps, 17, monthBenchmark == null ? BigDecimal.ZERO : monthBenchmark);
            for (int day = 1; day <= 31; day++) {
              BigDecimal value = valueDecimal(values, Integer.toString(day));
              if (value != null && day <= month.lengthOfMonth()) {
                dayTotal = dayTotal.add(value);
              }
              setBigDecimal(ps, 17 + day, value);
            }
            BigDecimal calculatedAverage =
                dayTotal.divide(BigDecimal.valueOf(month.lengthOfMonth()), 6, java.math.RoundingMode.HALF_UP);
            setBigDecimal(ps, 49, dayTotal);
            setBigDecimal(ps, 50, calculatedAverage);
            ps.setString(51, "PASS");
            ps.setString(52, null);
            setBigDecimal(ps, 53, monthBenchmark);
            setBigDecimal(ps, 54, dayTotal);
            ps.setString(55, row.valuesJson());
          }
        });
  }

  private String benchmarkSql() {
    var columns = new StringBuilder(
        """
        INSERT INTO benchmark_value
          (public_id, data_period, period_start, period_end, city_code, district_code,
           source_import_job_id, source_row_no, raw_row_json, billing_point_code,
           billing_point_name, billing_point_status, city_name, district_name, benchmark_year,
           benchmark_month, month_avg_benchmark
        """);
    for (int day = 1; day <= 31; day++) {
      columns.append(", day_").append(String.format("%02d", day));
    }
    columns.append(
        """
        , day_total, calculated_month_avg, validation_status, validation_message,
          benchmark_month_value, calculated_day_total, values_json)
        VALUES (
        """);
    columns.append(String.join(", ", java.util.Collections.nCopies(55, "?")));
    columns.append(")");
    return columns.toString();
  }

  private void deleteCurrentImportedBillingPoints(
      String tableName, ImportBatch batch, List<ImportRow> rows) {
    List<String> billingPointCodes = billingPointCodes(rows);
    if (billingPointCodes.isEmpty()) {
      return;
    }
    String placeholders = placeholders(billingPointCodes.size());
    var arguments = new java.util.ArrayList<Object>();
    arguments.add(batch.cityCode());
    arguments.add(batch.period());
    arguments.addAll(billingPointCodes);
    jdbcTemplate.update(
        "DELETE FROM "
            + tableName
            + " WHERE city_code = ? AND data_period = ? AND billing_point_code IN ("
            + placeholders
            + ")",
        arguments.toArray());
  }

  private List<String> billingPointCodes(List<ImportRow> rows) {
    var codes = new LinkedHashSet<String>();
    for (ImportRow row : rows) {
      if (row.billingPointCode() != null && !row.billingPointCode().isBlank()) {
        codes.add(row.billingPointCode());
      }
    }
    return List.copyOf(codes);
  }

  private String placeholders(int size) {
    return String.join(",", java.util.Collections.nCopies(size, "?"));
  }

  private abstract class RowSetter implements BatchPreparedStatementSetter {
    private final List<ImportRow> rows;

    RowSetter(List<ImportRow> rows) {
      this.rows = rows;
    }

    @Override
    public int getBatchSize() {
      return rows.size();
    }

    @Override
    public void setValues(PreparedStatement ps, int index) throws SQLException {
      ImportRow row = rows.get(index);
      setRow(ps, row, readMap(row.valuesJson()));
    }

    protected abstract void setRow(PreparedStatement ps, ImportRow row, Map<String, String> values)
        throws SQLException;
  }

  private Map<String, String> readMap(String json) {
    try {
      return objectMapper.readValue(json, STRING_MAP);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Import row JSON is invalid", exception);
    }
  }

  private String value(Map<String, String> values, String column) {
    String value = values.get(column);
    return value == null || value.isBlank() ? null : value.trim();
  }

  private String valueOr(Map<String, String> values, String column, String fallback) {
    String value = value(values, column);
    return value == null ? fallback : value;
  }

  private LocalDate requiredDate(Map<String, String> values, String column, String period) {
    LocalDate date = valueDate(values, column);
    return date == null ? YearMonth.parse(period).atDay(1) : date;
  }

  private LocalDate valueDate(Map<String, String> values, String column) {
    String raw = value(values, column);
    if (raw == null) {
      return null;
    }
    for (DateTimeFormatter formatter :
        List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"))) {
      try {
        return LocalDate.parse(raw, formatter);
      } catch (DateTimeParseException ignored) {
        // Try next supported format.
      }
    }
    return null;
  }

  private BigDecimal firstDecimal(Map<String, String> values, String first, String second) {
    BigDecimal value = valueDecimal(values, first);
    return value == null ? valueDecimal(values, second) : value;
  }

  private BigDecimal valueDecimal(Map<String, String> values, String column) {
    String raw = value(values, column);
    if (raw == null || "-".equals(raw) || "?".equals(raw) || "?".equals(raw)) {
      return null;
    }
    try {
      return new BigDecimal(raw.replace(",", "").replace("%", ""));
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private int intValue(Map<String, String> values, String column, int fallback) {
    String raw = value(values, column);
    if (raw == null) {
      return fallback;
    }
    try {
      return Integer.parseInt(raw.replaceFirst("^0", ""));
    } catch (RuntimeException exception) {
      return fallback;
    }
  }

  private void setDate(PreparedStatement ps, int index, LocalDate value) throws SQLException {
    if (value == null) {
      ps.setDate(index, null);
    } else {
      ps.setDate(index, Date.valueOf(value));
    }
  }

  private void setBigDecimal(PreparedStatement ps, int index, BigDecimal value) throws SQLException {
    ps.setBigDecimal(index, value);
  }
}
