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
    jdbcTemplate.update("DELETE FROM audit_result WHERE billing_point_code LIKE 'TEST-MULTI-%'");
    jdbcTemplate.update(
        "DELETE FROM billing_point_snapshot WHERE billing_point_code LIKE 'TEST-MULTI-%'");
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

  private void insertSnapshot(String code, String period) {
    jdbcTemplate.update(
        """
        INSERT INTO billing_point_snapshot (
            public_id, data_period, period_start, period_end, city_code, district_code,
            source_import_job_id, source_row_no, raw_row_json, billing_point_code,
            billing_point_name, city_name, data_json
        ) VALUES (?, ?, ?, ?, '320100', '320101', 1, 1, '{}', ?, ?, '南京市', '{}')
        """,
        UUID.randomUUID().toString(),
        period,
        period + "-01",
        period + "-28",
        code,
        code + "名称");
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
