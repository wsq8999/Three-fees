package com.threefees.dashboard.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.threefees.ThreeFeesApplication;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.domain.Role;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = ThreeFeesApplication.class,
    properties = {"app.bootstrap.enabled=true", "app.bootstrap.initial-password=test-password-123456"})
public class DashboardQueryServiceIntegrationTest {

  @Autowired private DashboardQueryService dashboardQueryService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute(
        "CREATE ALIAS IF NOT EXISTS JSON_EXTRACT FOR "
            + "'com.threefees.dashboard.application.DashboardQueryServiceIntegrationTest.jsonExtract'");
    jdbcTemplate.execute(
        "CREATE ALIAS IF NOT EXISTS JSON_UNQUOTE FOR "
            + "'com.threefees.dashboard.application.DashboardQueryServiceIntegrationTest.jsonUnquote'");
    jdbcTemplate.update("DELETE FROM audit_result WHERE billing_point_code LIKE 'BP-%'");
    jdbcTemplate.update("DELETE FROM billing_point_snapshot WHERE billing_point_code LIKE 'BP-%'");
    jdbcTemplate.update("DELETE FROM import_job WHERE created_by = 'test'");
  }

  @Test
  void pendingReportCountComesFromWaitingOverLimitAuditResults() {
    insertSnapshot("BP-PENDING-1", "320100", "2026-06");
    insertSnapshot("BP-PENDING-2", "320100", "2026-06");
    insertSnapshot("BP-GENERATED", "320100", "2026-06");
    insertSnapshot("BP-NORMAL", "320100", "2026-06");
    insertSnapshot("BP-OTHER-PERIOD", "320100", "2026-05");
    insertSnapshot("BP-OTHER-CITY", "321200", "2026-06");

    insertAudit("BP-PENDING-1", "320100", "2026-06", "OVER_LIMIT", "WAITING");
    insertAudit("BP-PENDING-2", "320100", "2026-06", "OVER_LIMIT", "WAITING");
    insertAudit("BP-GENERATED", "320100", "2026-06", "OVER_LIMIT", "GENERATED");
    insertAudit("BP-NORMAL", "320100", "2026-06", "NORMAL", "WAITING");
    insertAudit("BP-OTHER-PERIOD", "320100", "2026-05", "OVER_LIMIT", "WAITING");
    insertAudit("BP-OTHER-CITY", "321200", "2026-06", "OVER_LIMIT", "WAITING");

    DashboardSummary citySummary =
        dashboardQueryService.summarize(cityUser("320100", "南京市"), "2026-06");
    DashboardSummary adminSummary = dashboardQueryService.summarize(adminUser(), "2026-06");

    assertThat(citySummary.pendingReportCount()).isEqualTo(2);
    assertThat(citySummary.draftReportCount()).isEqualTo(2);
    assertThat(citySummary.finalReportCount()).isEqualTo(1);
    assertThat(citySummary.pendingTasks())
        .extracting(DashboardSummary.PendingReportTask::billingPointCode)
        .containsExactlyInAnyOrder("BP-PENDING-1", "BP-PENDING-2");
    assertThat(adminSummary.pendingReportCount()).isEqualTo(3);
    assertThat(adminSummary.finalReportCount()).isEqualTo(1);
  }

  @Test
  void generatedReportStatusImmediatelyReducesPendingReportCount() {
    insertSnapshot("BP-TO-GENERATE", "320100", "2026-07");
    insertAudit("BP-TO-GENERATE", "320100", "2026-07", "OVER_LIMIT", "WAITING");

    DashboardSummary before =
        dashboardQueryService.summarize(cityUser("320100", "南京市"), "2026-07");

    jdbcTemplate.update(
        """
        UPDATE audit_result
           SET report_status = 'GENERATED'
         WHERE billing_point_code = ? AND city_code = ? AND data_period = ?
        """,
        "BP-TO-GENERATE",
        "320100",
        "2026-07");
    DashboardSummary after =
        dashboardQueryService.summarize(cityUser("320100", "南京市"), "2026-07");

    assertThat(before.pendingReportCount()).isEqualTo(1);
    assertThat(before.finalReportCount()).isZero();
    assertThat(after.pendingReportCount()).isZero();
    assertThat(after.finalReportCount()).isEqualTo(1);
    assertThat(after.pendingTasks()).isEmpty();
  }

  @Test
  void listUpdatedAtComesFromActiveBillingPointImportCompletionTime() {
    insertSnapshot("BP-IMPORT-TIME", "320100", "2026-08");
    insertAudit("BP-IMPORT-TIME", "320100", "2026-08", "OVER_LIMIT", "WAITING");
    insertImportJob("BILLING_POINT", "320100", "2026-08", "ACTIVE", "2026-08-13 09:30:15.000");
    insertImportJob("BILLING_POINT", "320100", "2026-08", "FAILED", "2026-08-13 12:00:00.000");
    insertImportJob("PAYMENT", "320100", "2026-08", "ACTIVE", "2026-08-13 13:00:00.000");
    insertImportJob("BILLING_POINT", "321200", "2026-08", "ACTIVE", "2026-08-13 14:00:00.000");

    DashboardSummary citySummary =
        dashboardQueryService.summarize(cityUser("320100", "南京市"), "2026-08");
    DashboardSummary adminSummary = dashboardQueryService.summarize(adminUser(), "2026-08");

    assertThat(citySummary.lastUpdatedAt()).isEqualTo("2026-08-13T09:30:15");
    assertThat(adminSummary.lastUpdatedAt()).isEqualTo("2026-08-13T14:00:00");
  }

  private void insertSnapshot(String code, String cityCode, String period) {
    jdbcTemplate.update(
        """
        INSERT INTO billing_point_snapshot (
            public_id, data_period, period_start, period_end, city_code, district_code,
            source_import_job_id, source_row_no, raw_row_json, billing_point_code,
            billing_point_name, city_name, data_json
        ) VALUES (?, ?, ?, ?, ?, ?, 1, 1, '{}', ?, ?, ?, '{}')
        """,
        UUID.randomUUID().toString(),
        period,
        period + "-01",
        period + "-28",
        cityCode,
        cityCode + "01",
        code,
        code + "名称",
        cityCode.equals("321200") ? "泰州市" : "南京市");
  }

  private void insertAudit(
      String code, String cityCode, String period, String auditStatus, String reportStatus) {
    jdbcTemplate.update(
        """
        INSERT INTO audit_result (
            public_id, billing_point_code, billing_point_name, city_code, district_code,
            data_period, period_start, period_end, audit_status, report_status,
            max_ratio, over_limit_type
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0.35, 'MULTIPLE')
        """,
        UUID.randomUUID().toString(),
        code,
        code + "名称",
        cityCode,
        cityCode + "01",
        period,
        period + "-01",
        period + "-28",
        auditStatus,
        reportStatus);
  }

  private void insertImportJob(
      String datasetType, String cityCode, String period, String status, String completedAt) {
    jdbcTemplate.update(
        """
        INSERT INTO stored_file (
            public_id, storage_name, original_name, storage_path, media_type, file_ext,
            byte_size, sha256, purpose, created_by
        ) VALUES (?, ?, 'dashboard-test.xlsx', 'dashboard-test', 'application/octet-stream',
                  'xlsx', 1, ?, 'IMPORT_SOURCE', 'test')
        """,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString().replace("-", ""));
    Long fileId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM stored_file", Long.class);
    jdbcTemplate.update(
        """
        INSERT INTO import_job (
            public_id, task_public_id, dataset_type, data_period, city_code, source_file_id, status,
            errors_json, completed_at, created_by, updated_by
        ) VALUES (?, ?, ?, ?, ?, ?, ?, '[]', ?, 'test', 'test')
        """,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        datasetType,
        period,
        cityCode,
        fileId,
        status,
        completedAt);
  }

  private CurrentUser adminUser() {
    return new TestUser(null, null, Set.of(Role.SUPER_ADMIN));
  }

  private CurrentUser cityUser(String cityCode, String cityName) {
    return new TestUser(cityCode, cityName, Set.of(Role.CITY_USER));
  }

  public static String jsonExtract(String json, String path) {
    return null;
  }

  public static String jsonUnquote(String value) {
    return value;
  }

  private record TestUser(String cityCode, String cityName, Set<Role> roles) implements CurrentUser {
    @Override
    public long id() {
      return 1;
    }

    @Override
    public String username() {
      return "test";
    }

    @Override
    public String displayName() {
      return "Test User";
    }

    @Override
    public boolean mustChangePassword() {
      return false;
    }
  }
}
