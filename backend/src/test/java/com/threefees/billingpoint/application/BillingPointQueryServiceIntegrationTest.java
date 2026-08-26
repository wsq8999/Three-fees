package com.threefees.billingpoint.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.threefees.ThreeFeesApplication;
import com.threefees.billingpoint.application.BillingPointQueryService.BillingPointFilter;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.domain.Role;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

@SpringBootTest(
    classes = ThreeFeesApplication.class,
    properties = {
      "app.bootstrap.enabled=true",
      "app.bootstrap.initial-password=test-password-123456"
    })
class BillingPointQueryServiceIntegrationTest {

  private static final String TEST_PERIOD = "2026-11";

  @Autowired private BillingPointQueryService queryService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    cleanTestData();
  }

  @AfterEach
  void tearDown() {
    cleanTestData();
  }

  private void cleanTestData() {
    jdbcTemplate.update("DELETE FROM audit_result WHERE billing_point_code LIKE 'TEST-%'");
    jdbcTemplate.update(
        "DELETE FROM billing_point_snapshot WHERE billing_point_code LIKE 'TEST-%'");
  }

  @Test
  void multipleOverLimitSummaryDisplaysConcreteExceededItems() {
    insertSnapshot("TEST-MULTI-YOY-MOM", TEST_PERIOD);
    insertSnapshot("TEST-MULTI-YOY-RATED", TEST_PERIOD);
    insertSnapshot("TEST-MULTI-MOM-RATED", TEST_PERIOD);

    insertAudit("TEST-MULTI-YOY-MOM", "OVER_LIMIT", "OVER_LIMIT", "NORMAL");
    insertAudit("TEST-MULTI-YOY-RATED", "OVER_LIMIT", "NORMAL", "OVER_LIMIT");
    insertAudit("TEST-MULTI-MOM-RATED", "NORMAL", "OVER_LIMIT", "OVER_LIMIT");

    var summaries =
        queryService.findPage(
            new BillingPointFilter(
                null, null, "320100", null, TEST_PERIOD, null, null, null, null, null, null, null,
                null),
            0,
            20,
            administrator());

    assertThat(summaries.items())
        .filteredOn(item -> item.code().startsWith("TEST-MULTI-"))
        .extracting(
            BillingPointQueryService.BillingPointSummary::code,
            BillingPointQueryService.BillingPointSummary::overLimitDisplayType)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("TEST-MULTI-YOY-MOM", "同比、环比超标"),
            org.assertj.core.groups.Tuple.tuple("TEST-MULTI-YOY-RATED", "同比、额定标杆超标"),
            org.assertj.core.groups.Tuple.tuple("TEST-MULTI-MOM-RATED", "环比、额定标杆超标"));
  }

  @Test
  void multipleOverLimitDetailReasonDisplaysConcreteExceededItems() {
    insertSnapshot("TEST-MULTI-DETAIL", TEST_PERIOD);
    insertAudit("TEST-MULTI-DETAIL", "OVER_LIMIT", "OVER_LIMIT", "NORMAL");

    var summaries =
        queryService.findPage(
            new BillingPointFilter(
                null, null, "320100", null, TEST_PERIOD, null, null, null, null, null, null, null,
                null),
            0,
            20,
            administrator());

    var summary =
        summaries.items().stream()
            .filter(item -> item.code().equals("TEST-MULTI-DETAIL"))
            .findFirst()
            .orElseThrow();

    var detail = queryService.findDetail(summary.id(), administrator());

    assertThat(detail.audit().get("finalReason").asText())
        .isEqualTo("稽核结果超标，超标类型：同比、环比超标")
        .doesNotContain("多项超标");
  }

  @Test
  void detailAuditComparisonsSeparateDailyAndTotalValues() {
    insertSnapshot("TEST-MULTI-DAILY-TOTAL", TEST_PERIOD);
    insertAuditWithComparisonValues("TEST-MULTI-DAILY-TOTAL");

    var summaries =
        queryService.findPage(
            new BillingPointFilter(
                null, null, "320100", null, TEST_PERIOD, null, null, null, null, null, null, null,
                null),
            0,
            20,
            administrator());

    var summary =
        summaries.items().stream()
            .filter(item -> item.code().equals("TEST-MULTI-DAILY-TOTAL"))
            .findFirst()
            .orElseThrow();

    var detail = queryService.findDetail(summary.id(), administrator());
    var comparisons = detail.audit().get("comparisons");

    assertComparisonValues(comparisons.get(0), "YEAR_ON_YEAR", "15.5", "37.2", "20.25");
    assertComparisonValues(comparisons.get(1), "MONTH_ON_MONTH", "16.6", "29.88", "20.25");
    assertComparisonValues(comparisons.get(2), "RATED_BENCHMARK", "900", "900", "1200");
  }

  @Test
  void filterOptionsDistrictsComeFromFullFilteredResultSet() {
    insertSnapshotWithDistrict("TEST-OPT-11", TEST_PERIOD, "第十一页外区");
    insertAudit("TEST-OPT-11", "NORMAL", "NORMAL", "NORMAL");
    for (int index = 1; index <= 10; index++) {
      insertSnapshotWithDistrict(
          "TEST-OPT-" + String.format("%02d", index),
          TEST_PERIOD,
          "当前页区");
      insertAudit("TEST-OPT-" + String.format("%02d", index), "NORMAL", "NORMAL", "NORMAL");
    }

    var page =
        queryService.findPage(
            new BillingPointFilter(
                "TEST-OPT-", null, "320100", null, TEST_PERIOD, null, null, null, null,
                null, null, null, null),
            0,
            10,
            administrator());
    var options =
        queryService.filterOptions(
            new BillingPointFilter(
                "TEST-OPT-", null, "320100", null, TEST_PERIOD, null, null, null, null,
                null, null, null, null),
            administrator());

    assertThat(page.items()).hasSize(10);
    assertThat(page.items()).extracting(BillingPointQueryService.BillingPointSummary::district)
        .doesNotContain("第十一页外区");
    assertThat(options.districts()).contains("当前页区", "第十一页外区");
  }

  private void insertSnapshot(String code, String period) {
    insertSnapshotWithDistrict(code, period, "");
  }

  private void insertSnapshotWithDistrict(String code, String period, String district) {
    jdbcTemplate.update(
        """
        INSERT INTO billing_point_snapshot (
            public_id, data_period, period_start, period_end, city_code, district_code,
            district_name,
            source_import_job_id, source_row_no, raw_row_json, billing_point_code,
            billing_point_name, city_name, data_json
        ) VALUES (?, ?, ?, ?, '320100', '320101', ?, 1, 1, '{}', ?, ?, '南京市', ?)
        """,
        UUID.randomUUID().toString(),
        period,
        period + "-01",
        period + "-28",
        district,
        code,
        code + "名称",
        "{\"所属区县\":\"" + district + "\"}");
  }

  private void insertAudit(String code, String yoyResult, String momResult, String ratedResult) {
    jdbcTemplate.update(
        """
        INSERT INTO audit_result (
            public_id, billing_point_code, billing_point_name, city_code, district_code,
            data_period, period_start, period_end, audit_status, report_status,
            over_limit_type, max_ratio, yoy_result, mom_result, rated_result
        ) VALUES (?, ?, ?, '320100', '320101', ?, ?, ?,
                  'OVER_LIMIT', 'WAITING', 'MULTIPLE', 0.35, ?, ?, ?)
        """,
        UUID.randomUUID().toString(),
        code,
        code + "名称",
        TEST_PERIOD,
        TEST_PERIOD + "-01",
        TEST_PERIOD + "-28",
        yoyResult,
        momResult,
        ratedResult);
  }

  private void insertAuditWithComparisonValues(String code) {
    jdbcTemplate.update(
        """
        INSERT INTO audit_result (
            public_id, billing_point_code, billing_point_name, city_code, district_code,
            data_period, period_start, period_end, audit_status, report_status,
            over_limit_type, max_ratio, yoy_result, mom_result, rated_result,
            actual_energy, current_daily_avg_kwh, yoy_reference_energy,
            yoy_reference_daily_kwh_c, yoy_factor_k, yoy_threshold_daily_kwh, mom_reference_energy,
            mom_reference_daily_kwh_c, mom_factor_k, mom_threshold_daily_kwh, rated_benchmark_energy
        ) VALUES (?, ?, ?, '320100', '320101', ?, ?, ?,
                  'OVER_LIMIT', 'WAITING', 'MULTIPLE', 0.35,
                  'OVER_LIMIT', 'NORMAL', 'OVER_LIMIT',
                  1200.00, 20.25, 465.00,
                  15.50, 2.00, 22.20, 498.00,
                  16.60, 1.50, 23.30, 900.00)
        """,
        UUID.randomUUID().toString(),
        code,
        code + "名称",
        TEST_PERIOD,
        TEST_PERIOD + "-01",
        TEST_PERIOD + "-28");
  }

  private void assertComparisonValues(
      JsonNode comparison, String key, String baseline, String threshold, String actual) {
    assertThat(comparison.get("key").asText()).isEqualTo(key);
    assertThat(comparison.get("baseline").asText()).isEqualTo(baseline);
    assertThat(comparison.get("threshold").asText()).isEqualTo(threshold);
    assertThat(comparison.get("actual").asText()).isEqualTo(actual);
  }

  private CurrentUser administrator() {
    return new CurrentUser() {
      @Override
      public long id() {
        return 1L;
      }

      @Override
      public String username() {
        return "admin";
      }

      @Override
      public String displayName() {
        return "超级管理员";
      }

      @Override
      public String cityCode() {
        return "";
      }

      @Override
      public String cityName() {
        return "";
      }

      @Override
      public boolean mustChangePassword() {
        return false;
      }

      @Override
      public Set<Role> roles() {
        return Set.of(Role.SUPER_ADMIN);
      }
    };
  }
}
