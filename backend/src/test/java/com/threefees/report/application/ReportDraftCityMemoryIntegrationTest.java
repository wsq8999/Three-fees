package com.threefees.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.domain.Role;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {"three-fees.process-role=api", "app.bootstrap.enabled=false"})
class ReportDraftCityMemoryIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ReportDraftService service;

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update(
        "DELETE FROM report_draft_version WHERE draft_id IN (SELECT id FROM report_draft WHERE created_by='city-memory-test')");
    jdbcTemplate.update(
        "DELETE FROM report_draft WHERE created_by='city-memory-test'");
    jdbcTemplate.update("DELETE FROM audit_result WHERE billing_point_code='MEMORY-POINT-001'");
    jdbcTemplate.update("DELETE FROM billing_point_snapshot WHERE billing_point_code='MEMORY-POINT-001'");
    jdbcTemplate.update("DELETE FROM import_job WHERE created_by='city-memory-test'");
    jdbcTemplate.update("DELETE FROM stored_file WHERE created_by='city-memory-test'");
    jdbcTemplate.update("DELETE FROM ai_city_memory WHERE confirmed_by='city-memory-test'");
  }

  @Test
  void cityMemoryQueryNeverReturnsAnotherCityMemory() {
    insertMemory("320100", "南京空调运行变化");
    insertMemory("320500", "苏州设备扩容");

    var nanjing = service.cityMemoryReferences("320100");

    assertThat(nanjing).hasSize(1);
    assertThat(nanjing.getFirst().summary()).contains("南京空调运行变化");
    assertThat(nanjing.getFirst().summary()).doesNotContain("苏州设备扩容");
  }

  @Test
  void createsARecoverableInitialDraftAndVersionWithoutFastApi() {
    String snapshotId = seedOverLimitSnapshot();
    CurrentUser actor = mock(CurrentUser.class);
    when(actor.username()).thenReturn("city-memory-test");
    when(actor.cityCode()).thenReturn("320100");
    when(actor.roles()).thenReturn(Set.of(Role.CITY_USER));

    var draft = service.createOrResume(snapshotId, actor);

    assertThat(draft.cityCode()).isEqualTo("320100");
    assertThat(draft.sections().title()).contains("电费稽核说明");
    assertThat(draft.currentVersion()).isZero();
    assertThat(service.versions(draft.publicId(), actor)).hasSize(1);
  }

  private void insertMemory(String cityCode, String reason) {
    jdbcTemplate.update(
        """
        INSERT INTO ai_city_memory
          (public_id, city_code, final_reason, trust_level, confirmed_by)
        VALUES (?, ?, ?, 'CONFIRMED_REPORT', 'city-memory-test')
        """,
        UUID.randomUUID().toString(),
        cityCode,
        reason);
  }

  private String seedOverLimitSnapshot() {
    String fileId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO stored_file
          (public_id, storage_name, original_name, media_type, byte_size, sha256, purpose, created_by)
        VALUES (?, ?, 'seed.csv', 'text/csv', 1, ?, 'TEST', 'city-memory-test')
        """,
        fileId,
        fileId + ".csv",
        "0".repeat(64));
    Long storedFileId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM stored_file WHERE public_id=?", Long.class, fileId);
    String importId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO import_job
          (public_id, dataset_type, data_period, city_code, status, source_file_id,
           task_public_id, errors_json, created_by, updated_by)
        VALUES (?, 'BILLING_POINT', '2026-07', '320100', 'SUCCEEDED', ?, ?, '[]',
                'city-memory-test', 'city-memory-test')
        """,
        importId,
        storedFileId,
        UUID.randomUUID().toString());
    Long importDbId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM import_job WHERE public_id=?", Long.class, importId);
    String snapshotId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO billing_point_snapshot
          (public_id, data_period, period_start, period_end, city_code,
           source_import_job_id, source_row_no, raw_row_json, billing_point_code,
           billing_point_name, city_name, data_json)
        VALUES (?, '2026-07', '2026-07-01', '2026-07-31', '320100', ?, 1, '{}',
                'MEMORY-POINT-001', '南京测试报账点', '南京市', '{}')
        """,
        snapshotId,
        importDbId);
    jdbcTemplate.update(
        """
        INSERT INTO audit_result
          (public_id, billing_point_code, billing_point_name, city_code, data_period,
           period_start, period_end, audit_status, report_status, over_limit_type,
           max_ratio, detail_json)
        VALUES (?, 'MEMORY-POINT-001', '南京测试报账点', '320100', '2026-07',
                '2026-07-01', '2026-07-31', 'OVER_LIMIT', 'WAITING', 'ONLY_MOM', 18.5, '{}')
        """,
        UUID.randomUUID().toString());
    return snapshotId;
  }
}
