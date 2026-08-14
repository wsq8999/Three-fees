package com.threefees.importing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.threefees.ThreeFeesApplication;
import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ImportBatch;
import com.threefees.importing.domain.ImportBatchStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = ThreeFeesApplication.class,
    properties = {"app.bootstrap.enabled=true", "app.bootstrap.initial-password=test-password-123456"})
class FormalImportTableWriterIntegrationTest {

  @Autowired private FormalImportTableWriter writer;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void billingPointImportUpsertsSamePointAndKeepsOtherPointsInSameCityPeriod() {
    ImportBatch batch = batch(DatasetType.BILLING_POINT, "2026-06", "320100");

    writer.replace(
        batch,
        List.of(
            row(2, "320100", "BP-001", "报账点一", null, null, billingPointJson("BP-001", "报账点一", "南京市", "玄武区")),
            row(3, "320100", "BP-002", "报账点二", null, null, billingPointJson("BP-002", "报账点二", "南京市", "鼓楼区"))));
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

    assertThat(count("billing_point_snapshot", "2026-06", "320100")).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT billing_point_name FROM billing_point_snapshot WHERE city_code=? AND data_period=? AND billing_point_code=?",
                String.class,
                "320100",
                "2026-06",
                "BP-001"))
        .isEqualTo("报账点一-更新");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT billing_point_name FROM billing_point_snapshot WHERE city_code=? AND data_period=? AND billing_point_code=?",
                String.class,
                "320100",
                "2026-06",
                "BP-002"))
        .isEqualTo("报账点二");
  }

  @Test
  void paymentMeterAndBenchmarkReplaceOnlyImportedBillingPoints() {
    ImportBatch paymentBatch = batch(DatasetType.PAYMENT, "2026-07", "320100");
    ImportBatch meterBatch = batch(DatasetType.METER_READING, "2026-07", "320100");
    ImportBatch benchmarkBatch = batch(DatasetType.BENCHMARK, "2026-07", "320100");

    writer.replace(
        paymentBatch,
        List.of(
            row(2, "320100", "BP-101", "报账点一", "PAY-1", null, paymentJson("BP-101", "报账点一", "PAY-1", "100.00")),
            row(3, "320100", "BP-102", "报账点二", "PAY-2", null, paymentJson("BP-102", "报账点二", "PAY-2", "200.00"))));
    writer.replace(
        meterBatch,
        List.of(
            row(2, "320100", "BP-101", "报账点一", "PAY-1", "M-1", meterJson("BP-101", "报账点一", "PAY-1", "M-1", "10")),
            row(3, "320100", "BP-102", "报账点二", "PAY-2", "M-2", meterJson("BP-102", "报账点二", "PAY-2", "M-2", "20"))));
    writer.replace(
        benchmarkBatch,
        List.of(
            row(2, "320100", "BP-101", "报账点一", null, null, benchmarkJson("BP-101", "报账点一", "31")),
            row(3, "320100", "BP-102", "报账点二", null, null, benchmarkJson("BP-102", "报账点二", "62"))));

    writer.replace(
        paymentBatch,
        List.of(
            row(2, "320100", "BP-101", "报账点一", "PAY-1B", null, paymentJson("BP-101", "报账点一", "PAY-1B", "300.00"))));
    writer.replace(
        meterBatch,
        List.of(
            row(2, "320100", "BP-101", "报账点一", "PAY-1B", "M-1B", meterJson("BP-101", "报账点一", "PAY-1B", "M-1B", "30"))));
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
            row(2, "320100", "BP-SAME", "南京报账点", null, null, billingPointJson("BP-SAME", "南京报账点", "南京市", "玄武区"))));
    writer.replace(
        batch(DatasetType.BILLING_POINT, "2026-08", "321200"),
        List.of(
            row(2, "321200", "BP-SAME", "泰州报账点", null, null, billingPointJson("BP-SAME", "泰州报账点", "泰州市", "海陵区"))));

    assertThat(count("billing_point_snapshot", "2026-08", "320100")).isEqualTo(1);
    assertThat(count("billing_point_snapshot", "2026-08", "321200")).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM billing_point_master WHERE billing_point_code=?",
                Integer.class,
                "BP-SAME"))
        .isEqualTo(2);
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

  private String billingPointJson(
      String code, String name, String cityName, String districtName) {
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
        + jsonPair("实际报账金额", amount)
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
