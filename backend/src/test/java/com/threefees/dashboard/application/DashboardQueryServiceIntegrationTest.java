package com.threefees.dashboard.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.threefees.ThreeFeesApplication;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.domain.Role;
import com.threefees.report.application.AuditReportService;
import java.util.Set;
import java.util.UUID;
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
public class DashboardQueryServiceIntegrationTest {

  @Autowired private DashboardQueryService dashboardQueryService;
  @Autowired private AuditReportService auditReportService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute(
        "CREATE ALIAS IF NOT EXISTS JSON_EXTRACT FOR "
            + "'com.threefees.dashboard.application.DashboardQueryServiceIntegrationTest.jsonExtract'");
    jdbcTemplate.execute(
        "CREATE ALIAS IF NOT EXISTS JSON_UNQUOTE FOR "
            + "'com.threefees.dashboard.application.DashboardQueryServiceIntegrationTest.jsonUnquote'");
    jdbcTemplate.update(
        "DELETE FROM audit_report WHERE billing_point_snapshot_id IN "
            + "(SELECT id FROM billing_point_snapshot WHERE billing_point_code LIKE 'BP-%')");
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
    insertReport("BP-GENERATED", "320100", "2026-06", "GENERATED");

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
  void generatedAndImportedReportsImmediatelyLeavePendingTasks() {
    insertSnapshot("BP-SYSTEM-REPORT", "320100", "2026-07");
    insertSnapshot("BP-IMPORTED-REPORT", "320100", "2026-07");
    insertAudit("BP-SYSTEM-REPORT", "320100", "2026-07", "OVER_LIMIT", "WAITING");
    insertAudit("BP-IMPORTED-REPORT", "320100", "2026-07", "OVER_LIMIT", "WAITING");

    DashboardSummary before = dashboardQueryService.summarize(cityUser("320100", "南京市"), "2026-07");

    insertReport("BP-SYSTEM-REPORT", "320100", "2026-07", "GENERATED");
    insertReport("BP-IMPORTED-REPORT", "320100", "2026-07", "IMPORTED");
    DashboardSummary after = dashboardQueryService.summarize(cityUser("320100", "南京市"), "2026-07");

    assertThat(before.pendingReportCount()).isEqualTo(2);
    assertThat(before.finalReportCount()).isZero();
    assertThat(after.pendingReportCount()).isZero();
    assertThat(after.finalReportCount()).isEqualTo(2);
    assertThat(after.pendingTasks()).isEmpty();
  }

  @Test
  void listUpdatedAtComesFromActiveBillingPointImportCompletionTime() {
    insertSnapshot("BP-IMPORT-TIME", "320100", "2026-08");
    insertAudit("BP-IMPORT-TIME", "320100", "2026-08", "OVER_LIMIT", "WAITING");
    insertImportJob("BILLING_POINT", "320100", "MASTER", "ACTIVE", "2026-08-13 09:30:15.000");
    insertImportJob("BILLING_POINT", "320100", "MASTER", "FAILED", "2026-08-13 12:00:00.000");
    insertImportJob("PAYMENT", "320100", "2026-08", "ACTIVE", "2026-08-13 13:00:00.000");
    insertImportJob("BILLING_POINT", "321200", "MASTER", "ACTIVE", "2026-08-13 14:00:00.000");

    DashboardSummary citySummary =
        dashboardQueryService.summarize(cityUser("320100", "南京市"), "2026-08");
    DashboardSummary adminSummary = dashboardQueryService.summarize(adminUser(), "2026-08");

    assertThat(citySummary.lastUpdatedAt()).isEqualTo("2026-08-13T09:30:15");
    assertThat(adminSummary.lastUpdatedAt()).isEqualTo("2026-08-13T14:00:00");
  }

  @Test
  void monthlySnapshotCountsAndChartsUseRequestedPeriod() {
    insertSnapshot("BP-MONTHLY-NORMAL", "320100", "2026-09", "玄武区", "SITE-A");
    insertSnapshot("BP-MONTHLY-OVER-1", "320100", "2026-09", "玄武区", "SITE-B");
    insertSnapshot("BP-MONTHLY-OVER-2", "320100", "2026-09", "鼓楼区", "SITE-C");
    insertSnapshot("BP-MONTHLY-PENDING", "320100", "2026-09", "秦淮区", "SITE-D");
    insertSnapshot("BP-MONTHLY-OTHER-PERIOD", "320100", "2026-08", "玄武区", "SITE-E");

    insertAudit("BP-MONTHLY-NORMAL", "320100", "2026-09", "NORMAL", "WAITING");
    insertAudit("BP-MONTHLY-OVER-1", "320100", "2026-09", "OVER_LIMIT", "WAITING");
    insertAudit("BP-MONTHLY-OVER-2", "320100", "2026-09", "OVER_LIMIT", "WAITING");

    DashboardSummary summary =
        dashboardQueryService.summarize(cityUser("320100", "南京市"), "2026-09");

    assertThat(summary.billingPointCount()).isEqualTo(4);
    assertThat(summary.normalBillingPointCount()).isEqualTo(1);
    assertThat(summary.overLimitBillingPointCount()).isEqualTo(2);
    assertThat(summary.pendingReviewCount()).isEqualTo(1);
    assertThat(summary.districtOverLimitCounts())
        .extracting(DashboardSummary.NameCount::name, DashboardSummary.NameCount::count)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("玄武区", 1L),
            org.assertj.core.groups.Tuple.tuple("鼓楼区", 1L));
    assertThat(summary.overLimitTypeCounts())
        .extracting(DashboardSummary.NameCount::name, DashboardSummary.NameCount::count)
        .contains(org.assertj.core.groups.Tuple.tuple("多项超标", 2L));
  }

  @Test
  void reportFilterOptionsAreNotCappedByListPageSize() {
    for (int index = 1; index <= 101; index++) {
      String code = "BP-REPORT-OPTION-" + index;
      String district = index == 101 ? "第101区" : "普通区";
      insertSnapshot(code, "320100", "2026-10", district, "SITE-REPORT-" + index);
      insertAudit(code, "320100", "2026-10", "OVER_LIMIT", "WAITING");
      insertReport(code, "320100", "2026-10", "GENERATED");
    }

    var options =
        auditReportService.filterOptions(null, null, null, "2026-10", "320100", null, adminUser());

    assertThat(options.districts()).contains("普通区", "第101区");
  }

  private void insertSnapshot(String code, String cityCode, String period) {
    insertSnapshot(code, cityCode, period, "", "");
  }

  private void insertSnapshot(
      String code, String cityCode, String period, String district, String siteCode) {
    String json =
        "{\"所属区县\":\""
            + district
            + "\",\"关联资源编码\":\""
            + siteCode
            + "\"}";
    jdbcTemplate.update(
        """
        INSERT INTO billing_point_snapshot (
            public_id, data_period, period_start, period_end, city_code, district_code,
            district_name,
            source_import_job_id, source_row_no, raw_row_json, billing_point_code,
            billing_point_name, city_name, data_json
        ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, '{}', ?, ?, ?, ?)
        """,
        UUID.randomUUID().toString(),
        period,
        period + "-01",
        period + "-28",
        cityCode,
        cityCode + "01",
        district,
        code,
        code + "名称",
        cityCode.equals("321200") ? "泰州市" : "南京市",
        json);
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

  private void insertReport(String code, String cityCode, String period, String sourceType) {
    jdbcTemplate.update(
        """
        INSERT INTO stored_file (
            public_id, storage_name, original_name, storage_path, media_type, file_ext,
            byte_size, sha256, purpose, created_by
        ) VALUES (?, ?, 'dashboard-report.docx', 'dashboard-report', 'application/octet-stream',
                  'docx', 1, ?, 'FORMAL_REPORT_WORD', 'test')
        """,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString().replace("-", ""));
    Long fileId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM stored_file", Long.class);
    Long snapshotId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM billing_point_snapshot "
                + "WHERE billing_point_code=? AND city_code=? AND data_period=?",
            Long.class,
            code,
            cityCode,
            period);
    jdbcTemplate.update(
        """
        INSERT INTO audit_report (
            public_id, report_number, billing_point_snapshot_id, source_type, status,
            title, situation, analysis, rectification, word_file_id, pdf_file_id,
            business_snapshot_json, updated_by
        ) VALUES (?, ?, ?, ?, 'GENERATED', ?, '', '', '', ?, ?, '{}', 'test')
        """,
        UUID.randomUUID().toString(),
        "BG-" + UUID.randomUUID().toString().substring(0, 12),
        snapshotId,
        sourceType,
        code + "报告",
        fileId,
        fileId);
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
    if (json == null || path == null) {
      return null;
    }
    if (path.contains("所属区县")) {
      return jsonValue(json, "所属区县");
    }
    if (path.contains("关联资源编码")) {
      return jsonValue(json, "关联资源编码");
    }
    return null;
  }

  private static String jsonValue(String json, String key) {
    String quotedKey = "\"" + key + "\"";
    int keyIndex = json.indexOf(quotedKey);
    if (keyIndex < 0) {
      return null;
    }
    int colon = json.indexOf(':', keyIndex + quotedKey.length());
    if (colon < 0) {
      return null;
    }
    int valueStart = json.indexOf('"', colon);
    if (valueStart < 0) {
      return null;
    }
    int valueEnd = json.indexOf('"', valueStart + 1);
    return valueEnd < 0 ? null : json.substring(valueStart + 1, valueEnd);
  }

  public static String jsonUnquote(String value) {
    return value;
  }

  private record TestUser(String cityCode, String cityName, Set<Role> roles)
      implements CurrentUser {
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
