package com.threefees.importing.application;

import com.threefees.importing.domain.ImportBatch;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
  private static final String LAST_PERIOD_END = "最后报账期终";
  private static final String PERIOD_START = "缴费期始";
  private static final String PERIOD_END = "缴费期终";
  private static final String PAYMENT_DAYS = "缴费天数";
  private static final String DAILY_AVG_KWH = "日均耗电量";
  private static final String ACTUAL_AMOUNT = "实际报账金额";
  private static final String ACTUAL_TOTAL_KWH = "实际总耗电量";
  private static final String BENCHMARK_OVER_LIMIT = "标杆是否超标";
  private static final String HISTORICAL_FEE_BENCHMARK_YOY = "历史电费标杆-同比";
  private static final String HISTORICAL_FEE_BENCHMARK_MOM = "历史电费标杆-环比";
  private static final String HISTORICAL_DAILY_ENERGY_YOY = "历史日均电量标杆-同比";
  private static final String HISTORICAL_DAILY_ENERGY_MOM = "历史日均电量标杆-环比";
  private static final String RATED_POWER_BENCHMARK = "额定功率标杆";
  private static final String METER_ACCOUNT_NO = "电表户号";
  private static final String METER_MULTIPLIER = "电表倍率";
  private static final String METER_STATUS = "电表状态";
  private static final String ALLOCATED_ENERGY = "分摊后度数";
  private static final String RESOURCE_CODE = "关联资源编码";
  private static final String RESOURCE_NAME = "关联资源名称";
  private static final String METER_CODE = "关联电表编码";
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
    List<ImportRow> aggregatedRows = aggregateBillingPointRows(rows);
    syncBillingPointMaster(batch, aggregatedRows);
  }

  private List<ImportRow> aggregateBillingPointRows(List<ImportRow> rows) {
    var grouped = new LinkedHashMap<String, List<ImportRow>>();
    for (ImportRow row : rows) {
      grouped
          .computeIfAbsent(row.billingPointCode(), ignored -> new java.util.ArrayList<>())
          .add(row);
    }
    var aggregated = new java.util.ArrayList<ImportRow>();
    for (List<ImportRow> group : grouped.values()) {
      ImportRow first = group.getFirst();
      Map<String, String> merged = new LinkedHashMap<>(readMap(first.valuesJson()));
      mergeMultiValue(merged, group, RESOURCE_CODE);
      mergeMultiValue(merged, group, RESOURCE_NAME);
      mergeMultiValue(merged, group, METER_CODE);
      mergeMultiValue(merged, group, METER_ACCOUNT_NO);
      mergeMultiValue(merged, group, METER_MULTIPLIER);
      mergeMultiValue(merged, group, METER_STATUS);
      aggregated.add(
          new ImportRow(
              first.sourceRow(),
              first.cityCode(),
              first.billingPointCode(),
              first.billingPointName(),
              first.paymentCode(),
              first.meterCode(),
              first.businessKey(),
              writeJson(merged)));
    }
    return List.copyOf(aggregated);
  }

  private void mergeMultiValue(Map<String, String> target, List<ImportRow> rows, String column) {
    var values = new LinkedHashSet<String>();
    for (ImportRow row : rows) {
      String value = value(readMap(row.valuesJson()), column);
      if (value != null && !value.isBlank()) {
        values.add(value);
      }
    }
    if (!values.isEmpty()) {
      target.put(column, String.join("、", values));
    }
  }

  private void syncBillingPointMaster(ImportBatch batch, List<ImportRow> rows) {
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO billing_point_master
          (billing_point_code, billing_point_name, city_code, district_code,
           billing_point_status, current_data_period, current_snapshot_id, resource_summary_json)
        VALUES (?, ?, ?, ?, ?, NULL, NULL, ?)
        ON DUPLICATE KEY UPDATE
          billing_point_name = VALUES(billing_point_name),
          district_code = VALUES(district_code),
          billing_point_status = VALUES(billing_point_status),
          resource_summary_json = VALUES(resource_summary_json),
          updated_at = CURRENT_TIMESTAMP(3)
        """
            ,
        new RowSetter(rows) {
          @Override
          protected void setRow(PreparedStatement ps, ImportRow row, Map<String, String> values)
              throws SQLException {
            ps.setString(1, row.billingPointCode());
            ps.setString(2, row.billingPointName());
            ps.setString(3, batch.cityCode());
            ps.setString(4, value(values, DISTRICT));
            ps.setString(5, value(values, BILLING_POINT_STATUS));
            ps.setString(6, row.valuesJson());
          }
        });
  }

  private void replacePayments(ImportBatch batch, List<ImportRow> rows) {
    deleteCurrentImportedBillingPoints("payment_detail", batch, rows);
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO payment_detail
          (public_id, data_period, period_start, period_end, city_code, district_code,
           source_import_job_id, source_row_no, raw_row_json, audit_status, payment_bill_code,
           city_name, district_name, billing_point_code, billing_point_name, payment_start,
           payment_end, payment_days, daily_avg_kwh, actual_report_amount, actual_total_kwh,
           benchmark_over_limit, historical_fee_benchmark_yoy, historical_fee_benchmark_mom,
           historical_daily_energy_yoy, historical_daily_energy_mom, rated_power_benchmark,
           values_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            setInteger(ps, 18, valueInteger(values, PAYMENT_DAYS));
            setBigDecimal(ps, 19, valueDecimal(values, DAILY_AVG_KWH));
            setBigDecimal(ps, 20, valueDecimal(values, ACTUAL_AMOUNT));
            setBigDecimal(ps, 21, valueDecimal(values, ACTUAL_TOTAL_KWH));
            ps.setString(22, value(values, BENCHMARK_OVER_LIMIT));
            ps.setString(23, value(values, HISTORICAL_FEE_BENCHMARK_YOY));
            ps.setString(24, value(values, HISTORICAL_FEE_BENCHMARK_MOM));
            setBigDecimal(ps, 25, valueDecimal(values, HISTORICAL_DAILY_ENERGY_YOY));
            setBigDecimal(ps, 26, valueDecimal(values, HISTORICAL_DAILY_ENERGY_MOM));
            setBigDecimal(ps, 27, valueDecimal(values, RATED_POWER_BENCHMARK));
            ps.setString(28, row.valuesJson());
          }
        });
    syncBusinessSnapshots(batch, rows);
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
            BigDecimal monthBenchmark =
                firstDecimal(values, MONTHLY_BENCHMARK, MONTHLY_AVERAGE_BENCHMARK);
            setBigDecimal(ps, 17, monthBenchmark == null ? BigDecimal.ZERO : monthBenchmark);
            for (int day = 1; day <= 31; day++) {
              BigDecimal value = valueDecimal(values, Integer.toString(day));
              if (value != null && day <= month.lengthOfMonth()) {
                dayTotal = dayTotal.add(value);
              }
              setBigDecimal(ps, 17 + day, value);
            }
            BigDecimal effectiveBenchmarkTotal =
                monthBenchmark == null ? dayTotal : monthBenchmark;
            BigDecimal calculatedAverage =
                effectiveBenchmarkTotal.divide(
                    BigDecimal.valueOf(month.lengthOfMonth()), 6, java.math.RoundingMode.HALF_UP);
            setBigDecimal(ps, 49, dayTotal);
            setBigDecimal(ps, 50, calculatedAverage);
            ps.setString(51, "PASS");
            ps.setString(52, null);
            setBigDecimal(ps, 53, monthBenchmark);
            setBigDecimal(ps, 54, effectiveBenchmarkTotal);
            ps.setString(55, row.valuesJson());
          }
        });
  }

  private void syncBusinessSnapshots(ImportBatch batch, List<ImportRow> rows) {
    List<String> billingPointCodes = billingPointCodes(rows);
    if (billingPointCodes.isEmpty()) {
      return;
    }
    String placeholders = placeholders(billingPointCodes.size());
    var arguments = new java.util.ArrayList<Object>();
    arguments.add(batch.cityCode());
    arguments.add(batch.period());
    arguments.addAll(billingPointCodes);
    List<SnapshotSeed> seeds =
        jdbcTemplate.query(
            """
            SELECT m.billing_point_code,
                   COALESCE(MIN(p.billing_point_name), m.billing_point_name) AS billing_point_name,
                   m.city_code,
                   COALESCE(MIN(p.city_name), c.name) AS city_name,
                   COALESCE(MIN(p.district_code), m.district_code) AS district_code,
                   m.billing_point_status,
                   MIN(p.values_json) AS data_json,
                   MIN(p.source_import_job_id) AS source_import_job_id,
                   MIN(p.source_row_no) AS source_row_no,
                   MIN(p.period_start) AS period_start,
                   MAX(p.period_end) AS period_end
              FROM billing_point_master m
              JOIN city c ON c.code = m.city_code
              JOIN payment_detail p
                ON p.city_code = m.city_code
               AND p.billing_point_code = m.billing_point_code
               AND p.data_period = ?
             WHERE m.city_code = ?
               AND m.billing_point_code IN (
            """
                + placeholders
                + """
               )
             GROUP BY m.billing_point_code, m.billing_point_name, m.city_code, c.name,
                      m.district_code, m.billing_point_status
            """,
            (resultSet, rowNumber) ->
                new SnapshotSeed(
                    resultSet.getString("billing_point_code"),
                    resultSet.getString("billing_point_name"),
                    resultSet.getString("city_code"),
                    resultSet.getString("city_name"),
                    resultSet.getString("district_code"),
                    resultSet.getString("billing_point_status"),
                    resultSet.getString("data_json"),
                    resultSet.getLong("source_import_job_id"),
                    resultSet.getInt("source_row_no"),
                    resultSet.getObject("period_start", LocalDate.class),
                    resultSet.getObject("period_end", LocalDate.class)),
            snapshotArguments(batch, billingPointCodes).toArray());
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO billing_point_snapshot
          (public_id, data_period, period_start, period_end, city_code, district_code,
           source_import_job_id, source_row_no, raw_row_json, source_audit_status,
           billing_point_code, billing_point_name, billing_point_type, city_name, district_name,
           billing_point_status, last_reimbursement_start, last_reimbursement_end, data_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, ?, ?, ?, NULL, NULL, ?)
        ON DUPLICATE KEY UPDATE
          billing_point_name = VALUES(billing_point_name),
          district_code = VALUES(district_code),
          source_import_job_id = VALUES(source_import_job_id),
          source_row_no = VALUES(source_row_no),
          raw_row_json = VALUES(raw_row_json),
          city_name = VALUES(city_name),
          district_name = VALUES(district_name),
          billing_point_status = VALUES(billing_point_status),
          data_json = VALUES(data_json),
          updated_at = CURRENT_TIMESTAMP(3)
        """,
        new BatchPreparedStatementSetter() {
          @Override
          public int getBatchSize() {
            return seeds.size();
          }

          @Override
          public void setValues(PreparedStatement ps, int index) throws SQLException {
            SnapshotSeed seed = seeds.get(index);
            String json =
                seed.dataJson() == null || seed.dataJson().isBlank()
                    ? fallbackSnapshotJson(seed)
                    : seed.dataJson();
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, batch.period());
            setDate(ps, 3, seed.periodStart());
            setDate(ps, 4, seed.periodEnd());
            ps.setString(5, seed.cityCode());
            ps.setString(6, seed.districtCode());
            ps.setLong(7, seed.sourceImportJobId());
            ps.setInt(8, seed.sourceRowNo());
            ps.setString(9, json);
            ps.setString(10, seed.billingPointCode());
            ps.setString(11, seed.billingPointName());
            ps.setString(12, seed.cityName());
            ps.setString(13, seed.districtCode());
            ps.setString(14, seed.billingPointStatus());
            ps.setString(15, json);
          }
        });
  }

  private java.util.ArrayList<Object> snapshotArguments(ImportBatch batch, List<String> codes) {
    var arguments = new java.util.ArrayList<Object>();
    arguments.add(batch.period());
    arguments.add(batch.cityCode());
    arguments.addAll(codes);
    return arguments;
  }

  private String fallbackSnapshotJson(SnapshotSeed seed) {
    var values = new LinkedHashMap<String, String>();
    values.put("报账点编码", seed.billingPointCode());
    values.put("报账点名称", seed.billingPointName());
    values.put("所属地市", seed.cityName());
    values.put("所属区县", seed.districtCode());
    values.put("报账点状态", seed.billingPointStatus());
    return writeJson(values);
  }

  private String benchmarkSql() {
    var columns =
        new StringBuilder(
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

  private String writeJson(Map<String, String> values) {
    try {
      return objectMapper.writeValueAsString(values);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Import row JSON could not be serialized", exception);
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

  private Integer valueInteger(Map<String, String> values, String column) {
    String raw = value(values, column);
    if (raw == null || "-".equals(raw) || "?".equals(raw)) {
      return null;
    }
    try {
      return Integer.valueOf(raw.replace(",", ""));
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private void setDate(PreparedStatement ps, int index, LocalDate value) throws SQLException {
    if (value == null) {
      ps.setDate(index, null);
    } else {
      ps.setDate(index, Date.valueOf(value));
    }
  }

  private void setBigDecimal(PreparedStatement ps, int index, BigDecimal value)
      throws SQLException {
    ps.setBigDecimal(index, value);
  }

  private void setInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
    if (value == null) {
      ps.setNull(index, java.sql.Types.INTEGER);
    } else {
      ps.setInt(index, value);
    }
  }

  private record SnapshotSeed(
      String billingPointCode,
      String billingPointName,
      String cityCode,
      String cityName,
      String districtCode,
      String billingPointStatus,
      String dataJson,
      long sourceImportJobId,
      int sourceRowNo,
      LocalDate periodStart,
      LocalDate periodEnd) {}
}
