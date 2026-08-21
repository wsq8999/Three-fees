package com.threefees.importing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.threefees.ThreeFeesApplication;
import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ImportBatch;
import com.threefees.importing.domain.ImportBatchStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = ThreeFeesApplication.class,
    properties = {
      "app.bootstrap.enabled=true",
      "app.bootstrap.initial-password=test-password-123456"
    })
class FormalImportTableWriterIntegrationTest {

  @Autowired private FormalImportTableWriter writer;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void cleanTestData() {
    jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
    jdbcTemplate.update("DELETE FROM audit_report WHERE billing_point_snapshot_id IN (SELECT id FROM billing_point_snapshot WHERE billing_point_code LIKE 'BP-%')");
    jdbcTemplate.update("DELETE FROM report_draft WHERE billing_point_snapshot_id IN (SELECT id FROM billing_point_snapshot WHERE billing_point_code LIKE 'BP-%')");
    jdbcTemplate.update("DELETE FROM audit_result WHERE billing_point_code LIKE 'BP-%'");
    jdbcTemplate.update("DELETE FROM benchmark_value WHERE billing_point_code LIKE 'BP-%'");
    jdbcTemplate.update("DELETE FROM meter_reading WHERE billing_point_code LIKE 'BP-%'");
    jdbcTemplate.update("DELETE FROM payment_detail WHERE billing_point_code LIKE 'BP-%'");
    jdbcTemplate.update("DELETE FROM billing_point_snapshot WHERE billing_point_code LIKE 'BP-%'");
    jdbcTemplate.update("DELETE FROM billing_point_master WHERE billing_point_code LIKE 'BP-%'");
    jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
  }

  @Test
  void billingPointImportUpsertsSamePointAndKeepsOtherPointsInSameCityPeriod() {
    ImportBatch batch = batch(DatasetType.BILLING_POINT, "2026-06", "320100");

    writer.replace(
        batch,
        List.of(
            row(
                2,
                "320100",
                "BP-001",
                "报账点一",
                null,
                null,
                billingPointJson("BP-001", "报账点一", "南京市", "玄武区")),
            row(
                3,
                "320100",
                "BP-002",
                "报账点二",
                null,
                null,
                billingPointJson("BP-002", "报账点二", "南京市", "鼓楼区"))));
    writer.replace(
        batch,
        List.of(
            row(
                2,
                "320100",
                "BP-001",
                "报账点一-更新",
                null,
                null,
                billingPointJson("BP-001", "报账点一-更新", "南京市", "秦淮区"))));

    assertThat(count("billing_point_snapshot", "2026-06", "320100")).isZero();
    assertThat(countMaster("320100")).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT billing_point_name FROM billing_point_master WHERE city_code=? AND billing_point_code=?",
                String.class,
                "320100",
                "BP-001"))
        .isEqualTo("报账点一-更新");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT billing_point_name FROM billing_point_master WHERE city_code=? AND billing_point_code=?",
                String.class,
                "320100",
                "BP-002"))
        .isEqualTo("报账点二");
  }

  @Test
  void billingPointImportAggregatesMultipleSourceRowsIntoOneSnapshot() {
    ImportBatch batch = batch(DatasetType.BILLING_POINT, "2026-09", "320100");

    writer.replace(
        batch,
        List.of(
            row(
                2,
                "320100",
                "BP-MULTI",
                "多资源报账点",
                null,
                null,
                billingPointJsonWithResource(
                    "BP-MULTI", "多资源报账点", "南京市", "玄武区", "RES-1", "资源一", "M-1")),
            row(
                3,
                "320100",
                "BP-MULTI",
                "多资源报账点",
                null,
                null,
                billingPointJsonWithResource(
                    "BP-MULTI", "多资源报账点", "南京市", "玄武区", "RES-2", "资源二", "M-2"))));

    assertThat(countByPoint("billing_point_snapshot", "2026-09", "320100", "BP-MULTI")).isZero();
    String dataJson =
        jdbcTemplate.queryForObject(
            "SELECT resource_summary_json FROM billing_point_master WHERE city_code=? AND billing_point_code=?",
            String.class,
            "320100",
            "BP-MULTI");
    assertThat(dataJson).contains("RES-1、RES-2");
    assertThat(dataJson).contains("资源一、资源二");
    assertThat(dataJson).contains("M-1、M-2");
  }

  @Test
  void paymentMeterAndBenchmarkReplaceOnlyImportedBillingPoints() {
    ImportBatch paymentBatch = batch(DatasetType.PAYMENT, "2026-07", "320100");
    ImportBatch meterBatch = batch(DatasetType.METER_READING, "2026-07", "320100");
    ImportBatch benchmarkBatch = batch(DatasetType.BENCHMARK, "2026-07", "320100");

    writer.replace(
        paymentBatch,
        List.of(
            row(
                2,
                "320100",
                "BP-101",
                "报账点一",
                "PAY-1",
                null,
                paymentJson("BP-101", "报账点一", "PAY-1", "100.00")),
            row(
                3,
                "320100",
                "BP-102",
                "报账点二",
                "PAY-2",
                null,
                paymentJson("BP-102", "报账点二", "PAY-2", "200.00"))));
    writer.replace(
        meterBatch,
        List.of(
            row(
                2,
                "320100",
                "BP-101",
                "报账点一",
                "PAY-1",
                "M-1",
                meterJson("BP-101", "报账点一", "PAY-1", "M-1", "10")),
            row(
                3,
                "320100",
                "BP-102",
                "报账点二",
                "PAY-2",
                "M-2",
                meterJson("BP-102", "报账点二", "PAY-2", "M-2", "20"))));
    writer.replace(
        benchmarkBatch,
        List.of(
            row(2, "320100", "BP-101", "报账点一", null, null, benchmarkJson("BP-101", "报账点一", "31")),
            row(3, "320100", "BP-102", "报账点二", null, null, benchmarkJson("BP-102", "报账点二", "62"))));

    writer.replace(
        paymentBatch,
        List.of(
            row(
                2,
                "320100",
                "BP-101",
                "报账点一",
                "PAY-1B",
                null,
                paymentJson("BP-101", "报账点一", "PAY-1B", "300.00"))));
    writer.replace(
        meterBatch,
        List.of(
            row(
                2,
                "320100",
                "BP-101",
                "报账点一",
                "PAY-1B",
                "M-1B",
                meterJson("BP-101", "报账点一", "PAY-1B", "M-1B", "30"))));
    writer.replace(
        benchmarkBatch,
        List.of(
            row(2, "320100", "BP-101", "报账点一", null, null, benchmarkJson("BP-101", "报账点一", "93"))));

    assertThat(count("payment_detail", "2026-07", "320100")).isEqualTo(2);
    assertThat(countByPoint("payment_detail", "2026-07", "320100", "BP-101")).isEqualTo(1);
    assertThat(countByPoint("payment_detail", "2026-07", "320100", "BP-102")).isEqualTo(1);
    assertThat(countByPoint("meter_reading", "2026-07", "320100", "BP-101")).isEqualTo(1);
    assertThat(countByPoint("meter_reading", "2026-07", "320100", "BP-102")).isEqualTo(1);
    assertThat(countByPoint("benchmark_value", "2026-07", "320100", "BP-101")).isEqualTo(1);
    assertThat(countByPoint("benchmark_value", "2026-07", "320100", "BP-102")).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT payment_bill_code FROM payment_detail WHERE city_code=? AND data_period=? AND billing_point_code=?",
                String.class,
                "320100",
                "2026-07",
                "BP-101"))
        .isEqualTo("PAY-1B");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT payment_bill_code FROM payment_detail WHERE city_code=? AND data_period=? AND billing_point_code=?",
                String.class,
                "320100",
                "2026-07",
                "BP-102"))
        .isEqualTo("PAY-2");
  }

  @Test
  void sameBillingPointCodeAndPeriodInDifferentCitiesDoNotOverwriteEachOther() {
    writer.replace(
        batch(DatasetType.BILLING_POINT, "2026-08", "320100"),
        List.of(
            row(
                2,
                "320100",
                "BP-SAME",
                "南京报账点",
                null,
                null,
                billingPointJson("BP-SAME", "南京报账点", "南京市", "玄武区"))));
    writer.replace(
        batch(DatasetType.BILLING_POINT, "2026-08", "321200"),
        List.of(
            row(
                2,
                "321200",
                "BP-SAME",
                "泰州报账点",
                null,
                null,
                billingPointJson("BP-SAME", "泰州报账点", "泰州市", "海陵区"))));

    assertThat(count("billing_point_snapshot", "2026-08", "320100")).isZero();
    assertThat(count("billing_point_snapshot", "2026-08", "321200")).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_point_master WHERE billing_point_code=?",
                Integer.class,
                "BP-SAME"))
        .isEqualTo(2);
  }

  @Test
  void onlyPaymentImportCreatesBusinessSnapshot() {
    ImportBatch billingPointBatch = batch(DatasetType.BILLING_POINT, "2026-10", "320100");
    ImportBatch benchmarkBatch = batch(DatasetType.BENCHMARK, "2026-10", "320100");
    ImportBatch meterBatch = batch(DatasetType.METER_READING, "2026-10", "320100");
    ImportBatch paymentBatch = batch(DatasetType.PAYMENT, "2026-10", "320100");

    writer.replace(
        billingPointBatch,
        List.of(
            row(
                2,
                "320100",
                "BP-BENCH-ONLY",
                "仅标杆报账点",
                null,
                null,
                billingPointJson("BP-BENCH-ONLY", "仅标杆报账点", "南京市", "玄武区"))));

    writer.replace(
        benchmarkBatch,
        List.of(
            row(
                2,
                "320100",
                "BP-BENCH-ONLY",
                "仅标杆报账点",
                null,
                null,
                benchmarkJson("BP-BENCH-ONLY", "仅标杆报账点", "31"))));

    assertThat(countByPoint("benchmark_value", "2026-10", "320100", "BP-BENCH-ONLY"))
        .isEqualTo(1);
    assertThat(countByPoint("billing_point_snapshot", "2026-10", "320100", "BP-BENCH-ONLY"))
        .isZero();

    writer.replace(
        meterBatch,
        List.of(
            row(
                2,
                "320100",
                "BP-BENCH-ONLY",
                "仅标杆报账点",
                "PAY-BENCH-ONLY",
                "M-BENCH-ONLY",
                meterJson("BP-BENCH-ONLY", "仅标杆报账点", "PAY-BENCH-ONLY", "M-BENCH-ONLY", "30"))));

    assertThat(countByPoint("meter_reading", "2026-10", "320100", "BP-BENCH-ONLY"))
        .isEqualTo(1);
    assertThat(countByPoint("billing_point_snapshot", "2026-10", "320100", "BP-BENCH-ONLY"))
        .isZero();

    writer.replace(
        paymentBatch,
        List.of(
            row(
                2,
                "320100",
                "BP-BENCH-ONLY",
                "仅标杆报账点",
                "PAY-BENCH-ONLY",
                null,
                paymentJson("BP-BENCH-ONLY", "仅标杆报账点", "PAY-BENCH-ONLY", "100.00"))));

    assertThat(countByPoint("billing_point_snapshot", "2026-10", "320100", "BP-BENCH-ONLY"))
        .isEqualTo(1);
    assertThat(countByPoint("benchmark_value", "2026-10", "320100", "BP-BENCH-ONLY"))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT data_json FROM billing_point_snapshot WHERE city_code=? AND data_period=? AND billing_point_code=?",
                String.class,
                "320100",
                "2026-10",
                "BP-BENCH-ONLY"))
        .contains("缴费单编码");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT actual_total_kwh FROM payment_detail WHERE city_code=? AND data_period=? AND billing_point_code=?",
                java.math.BigDecimal.class,
                "320100",
                "2026-10",
                "BP-BENCH-ONLY"))
        .isEqualByComparingTo("310.00");
  }

  @Test
  void paymentImportPersistsExternalAuditBenchmarkFieldsWhenProvided() {
    ImportBatch paymentBatch = batch(DatasetType.PAYMENT, "2026-07", "320100");

    writer.replace(
        paymentBatch,
        List.of(
            row(
                2,
                "320100",
                "BP-AUDIT-FIELDS",
                "稽核字段报账点",
                "PAY-AUDIT-FIELDS",
                null,
                paymentJson("BP-AUDIT-FIELDS", "稽核字段报账点", "PAY-AUDIT-FIELDS", "100.00")
                    .replace(
                        "}",
                        ","
                            + jsonPair("标杆是否超标", "是")
                            + ","
                            + jsonPair("历史电费标杆-同比", "3.11%")
                            + ","
                            + jsonPair("历史电费标杆-环比", "否")
                            + ","
                            + jsonPair("历史日均电量标杆-同比", "8.12%")
                            + ","
                            + jsonPair("历史日均电量标杆-环比", "21.81%")
                            + ","
                            + jsonPair("额定功率标杆", "否")
                            + "}"))));

    Map<String, Object> stored =
        jdbcTemplate.queryForMap(
            """
            SELECT benchmark_over_limit, historical_fee_benchmark_yoy,
                   historical_fee_benchmark_mom, historical_daily_energy_yoy,
                   historical_daily_energy_mom, rated_power_benchmark
              FROM payment_detail
             WHERE city_code=? AND data_period=? AND billing_point_code=?
            """,
            "320100",
            "2026-07",
            "BP-AUDIT-FIELDS");

    assertThat(stored.get("BENCHMARK_OVER_LIMIT")).isEqualTo("是");
    assertThat(stored.get("HISTORICAL_FEE_BENCHMARK_YOY")).isEqualTo("3.11%");
    assertThat(stored.get("HISTORICAL_FEE_BENCHMARK_MOM")).isEqualTo("否");
    assertThat((java.math.BigDecimal) stored.get("HISTORICAL_DAILY_ENERGY_YOY"))
        .isEqualByComparingTo("8.12");
    assertThat((java.math.BigDecimal) stored.get("HISTORICAL_DAILY_ENERGY_MOM"))
        .isEqualByComparingTo("21.81");
    assertThat(stored.get("RATED_POWER_BENCHMARK")).isNull();
  }

  @Test
  void benchmarkImportUsesMonthlyBenchmarkAsAuditTotalWhenDailyValuesAreIncomplete() {
    ImportBatch benchmarkBatch = batch(DatasetType.BENCHMARK, "2026-07", "320100");

    writer.replace(
        benchmarkBatch,
        List.of(
            row(
                2,
                "320100",
                "BP-MONTH-BENCHMARK",
                "月总标杆报账点",
                null,
                null,
                benchmarkJson("BP-MONTH-BENCHMARK", "月总标杆报账点", "310.00"))));

    Map<String, Object> stored =
        jdbcTemplate.queryForMap(
            """
            SELECT day_total, calculated_month_avg, benchmark_month_value, calculated_day_total
              FROM benchmark_value
             WHERE city_code=? AND data_period=? AND billing_point_code=?
            """,
            "320100",
            "2026-07",
            "BP-MONTH-BENCHMARK");

    assertThat((java.math.BigDecimal) stored.get("DAY_TOTAL")).isEqualByComparingTo("2.00");
    assertThat((java.math.BigDecimal) stored.get("BENCHMARK_MONTH_VALUE"))
        .isEqualByComparingTo("310.00");
    assertThat((java.math.BigDecimal) stored.get("CALCULATED_DAY_TOTAL"))
        .isEqualByComparingTo("310.00");
    assertThat((java.math.BigDecimal) stored.get("CALCULATED_MONTH_AVG"))
        .isEqualByComparingTo("10.00");
  }

  private int countMaster(String cityCode) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM billing_point_master WHERE city_code = ?",
            Integer.class,
            cityCode);
    return count == null ? 0 : count;
  }

  private int count(String table, String period, String cityCode) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE city_code = ? AND data_period = ?",
            Integer.class,
            cityCode,
            period);
    return count == null ? 0 : count;
  }

  private int countByPoint(String table, String period, String cityCode, String billingPointCode) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM "
                + table
                + " WHERE city_code = ? AND data_period = ? AND billing_point_code = ?",
            Integer.class,
            cityCode,
            period,
            billingPointCode);
    return count == null ? 0 : count;
  }

  private ImportBatch batch(DatasetType type, String period, String cityCode) {
    return new ImportBatch(
        999,
        "batch-" + type.name() + "-" + period + "-" + cityCode,
        type,
        period,
        LocalDate.parse(period + "-01"),
        LocalDate.parse(period + "-28"),
        cityCode,
        ImportBatchStatus.PROCESSING,
        1,
        "task-1",
        0,
        0,
        List.of(),
        null,
        LocalDateTime.now(),
        "test",
        LocalDateTime.now(),
        0);
  }

  private ImportRow row(
      int sourceRow,
      String cityCode,
      String billingPointCode,
      String billingPointName,
      String paymentCode,
      String meterCode,
      String valuesJson) {
    return new ImportRow(
        sourceRow,
        cityCode,
        billingPointCode,
        billingPointName,
        paymentCode,
        meterCode,
        billingPointCode + "|" + sourceRow,
        valuesJson);
  }

  private String billingPointJson(String code, String name, String cityName, String districtName) {
    return "{"
        + jsonPair("报账点编码", code)
        + ","
        + jsonPair("报账点名称", name)
        + ","
        + jsonPair("所属地市", cityName)
        + ","
        + jsonPair("所属区县", districtName)
        + ","
        + jsonPair("报账点类型", "普通")
        + ","
        + jsonPair("报账点状态", "在用")
        + ","
        + jsonPair("最后报账期始", "2026-06-01")
        + ","
        + jsonPair("最后报账期止", "2026-06-30")
        + "}";
  }

  private String billingPointJsonWithResource(
      String code,
      String name,
      String cityName,
      String districtName,
      String resourceCode,
      String resourceName,
      String meterCode) {
    return billingPointJson(code, name, cityName, districtName)
        .replace(
            "}",
            ","
                + jsonPair("关联资源编码", resourceCode)
                + ","
                + jsonPair("关联资源名称", resourceName)
                + ","
                + jsonPair("关联电表编码", meterCode)
                + "}");
  }

  private String paymentJson(String code, String name, String paymentCode, String amount) {
    return "{"
        + jsonPair("报账点编码", code)
        + ","
        + jsonPair("报账点名称", name)
        + ","
        + jsonPair("缴费单编码", paymentCode)
        + ","
        + jsonPair("所属地市", "南京市")
        + ","
        + jsonPair("所属区县", "玄武区")
        + ","
        + jsonPair("审核状态", "审核通过")
        + ","
        + jsonPair("缴费期始", "2026-07-01")
        + ","
        + jsonPair("缴费期终", "2026-07-31")
        + ","
        + jsonPair("缴费天数", "31")
        + ","
        + jsonPair("日均耗电量", "10.00")
        + ","
        + jsonPair("实际报账金额", amount)
        + ","
        + jsonPair("实际总耗电量", "310.00")
        + "}";
  }

  private String meterJson(
      String code, String name, String paymentCode, String meterCode, String energy) {
    return "{"
        + jsonPair("报账点编码", code)
        + ","
        + jsonPair("报账点名称", name)
        + ","
        + jsonPair("缴费单编码", paymentCode)
        + ","
        + jsonPair("缴费期始", "2026-07-01")
        + ","
        + jsonPair("缴费期终", "2026-07-31")
        + ","
        + jsonPair("电表编码", meterCode)
        + ","
        + jsonPair("电表户号", "ACC-" + meterCode)
        + ","
        + jsonPair("电表倍率", "1")
        + ","
        + jsonPair("分摊后度数", energy)
        + "}";
  }

  private String benchmarkJson(String code, String name, String total) {
    return "{"
        + jsonPair("报账点编码", code)
        + ","
        + jsonPair("报账点名称", name)
        + ","
        + jsonPair("地市", "南京市")
        + ","
        + jsonPair("区县", "玄武区")
        + ","
        + jsonPair("报账点状态", "在用")
        + ","
        + jsonPair("年份", "2026")
        + ","
        + jsonPair("月份", "7")
        + ","
        + jsonPair("月总标杆", total)
        + ","
        + jsonPair("1", "1")
        + ","
        + jsonPair("2", "1")
        + "}";
  }

  private String jsonPair(String key, String value) {
    return "\"" + key + "\":\"" + value + "\"";
  }
}
