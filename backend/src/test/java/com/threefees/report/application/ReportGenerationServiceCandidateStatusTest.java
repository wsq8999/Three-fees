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

@SpringBootTest(properties = {"three-fees.process-role=api", "app.bootstrap.enabled=false"})
class ReportGenerationServiceCandidateStatusTest {

  private static final String CREATED_BY = "candidate-status-test";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ReportGenerationService service;

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("DELETE FROM audit_report WHERE updated_by = ?", CREATED_BY);
    jdbcTemplate.update("DELETE FROM report_draft WHERE created_by = ?", CREATED_BY);
    jdbcTemplate.update("DELETE FROM business_task WHERE created_by = ?", CREATED_BY);
    jdbcTemplate.update(
        """
        DELETE FROM audit_result
         WHERE billing_point_code IN
           ('CANDIDATE-NONE', 'CANDIDATE-QUEUED', 'CANDIDATE-ANALYZING',
            'CANDIDATE-RUNNING', 'CANDIDATE-RETRY', 'CANDIDATE-COMPLETED',
            'CANDIDATE-FAILED', 'CANDIDATE-DRAFT-ONLY', 'CANDIDATE-GENERATED')
        """);
    jdbcTemplate.update(
        """
        DELETE FROM billing_point_snapshot
         WHERE billing_point_code IN
           ('CANDIDATE-NONE', 'CANDIDATE-QUEUED', 'CANDIDATE-ANALYZING',
            'CANDIDATE-RUNNING', 'CANDIDATE-RETRY', 'CANDIDATE-COMPLETED',
            'CANDIDATE-FAILED', 'CANDIDATE-DRAFT-ONLY', 'CANDIDATE-GENERATED')
        """);
    jdbcTemplate.update("DELETE FROM import_job WHERE created_by = ?", CREATED_BY);
    jdbcTemplate.update("DELETE FROM stored_file WHERE created_by = ?", CREATED_BY);
  }

  @Test
  void candidatesExposeExistingDraftAndTaskStatusButExcludeFormalReports() {
    long noDraftSnapshot = seedOverLimitSnapshot("CANDIDATE-NONE", "无草稿报账点", "2026-04");
    long queuedSnapshot = seedOverLimitSnapshot("CANDIDATE-QUEUED", "排队中报账点", "2026-04");
    long runningSnapshot = seedOverLimitSnapshot("CANDIDATE-RUNNING", "分析中报账点", "2026-04");
    long retrySnapshot = seedOverLimitSnapshot("CANDIDATE-RETRY", "等待自动重试报账点", "2026-04");
    long completedSnapshot = seedOverLimitSnapshot("CANDIDATE-COMPLETED", "已分析报账点", "2026-04");
    long failedSnapshot = seedOverLimitSnapshot("CANDIDATE-FAILED", "分析失败报账点", "2026-04");
    long draftOnlySnapshot = seedOverLimitSnapshot("CANDIDATE-DRAFT-ONLY", "仅草稿状态报账点", "2026-04");
    long generatedSnapshot = seedOverLimitSnapshot("CANDIDATE-GENERATED", "已生成报账点", "2026-04");
    String queuedTaskId = UUID.randomUUID().toString();
    String queuedDraftId =
        seedDraft(queuedSnapshot, "CANDIDATE-QUEUED", "排队中报账点", "AI_ANALYZING", queuedTaskId);
    seedTask(queuedTaskId, "QUEUED");
    String runningTaskId = UUID.randomUUID().toString();
    String runningDraftId =
        seedDraft(runningSnapshot, "CANDIDATE-RUNNING", "分析中报账点", "AI_ANALYZING", runningTaskId);
    seedTask(runningTaskId, "RUNNING");
    String retryTaskId = UUID.randomUUID().toString();
    String retryDraftId =
        seedDraft(retrySnapshot, "CANDIDATE-RETRY", "等待自动重试报账点", "AI_ANALYZING", retryTaskId);
    seedTask(retryTaskId, "RETRY_WAIT");
    String completedTaskId = UUID.randomUUID().toString();
    String completedDraftId =
        seedDraft(
            completedSnapshot,
            "CANDIDATE-COMPLETED",
            "已分析报账点",
            "AI_COMPLETED_PENDING_CONFIRMATION",
            completedTaskId);
    seedTask(completedTaskId, "SUCCEEDED");
    String failedTaskId = UUID.randomUUID().toString();
    String failedDraftId =
        seedDraft(failedSnapshot, "CANDIDATE-FAILED", "分析失败报账点", "AI_FAILED", failedTaskId);
    seedTask(failedTaskId, "FAILED");
    seedDraft(
        draftOnlySnapshot,
        "CANDIDATE-DRAFT-ONLY",
        "仅草稿状态报账点",
        "AI_COMPLETED_PENDING_CONFIRMATION");
    seedFormalReport(generatedSnapshot);

    var candidates = service.candidates("320100", actor());

    assertThat(find(candidates, "CANDIDATE-NONE").draftId()).isNull();
    assertThat(find(candidates, "CANDIDATE-NONE").draftAnalysisStatus()).isNull();
    assertThat(find(candidates, "CANDIDATE-NONE").draftAnalysisTaskStatus()).isNull();
    assertThat(find(candidates, "CANDIDATE-QUEUED").draftId()).isEqualTo(queuedDraftId);
    assertThat(find(candidates, "CANDIDATE-QUEUED").draftAnalysisStatus()).isEqualTo("AI_ANALYZING");
    assertThat(find(candidates, "CANDIDATE-QUEUED").draftAnalysisTaskStatus()).isEqualTo("QUEUED");
    assertThat(find(candidates, "CANDIDATE-RUNNING").draftId()).isEqualTo(runningDraftId);
    assertThat(find(candidates, "CANDIDATE-RUNNING").draftAnalysisStatus()).isEqualTo("AI_ANALYZING");
    assertThat(find(candidates, "CANDIDATE-RUNNING").draftAnalysisTaskStatus()).isEqualTo("RUNNING");
    assertThat(find(candidates, "CANDIDATE-RETRY").draftId()).isEqualTo(retryDraftId);
    assertThat(find(candidates, "CANDIDATE-RETRY").draftAnalysisStatus()).isEqualTo("AI_ANALYZING");
    assertThat(find(candidates, "CANDIDATE-RETRY").draftAnalysisTaskStatus()).isEqualTo("RETRY_WAIT");
    assertThat(find(candidates, "CANDIDATE-COMPLETED").draftId()).isEqualTo(completedDraftId);
    assertThat(find(candidates, "CANDIDATE-COMPLETED").draftAnalysisStatus())
        .isEqualTo("AI_COMPLETED_PENDING_CONFIRMATION");
    assertThat(find(candidates, "CANDIDATE-COMPLETED").draftAnalysisTaskStatus()).isEqualTo("SUCCEEDED");
    assertThat(find(candidates, "CANDIDATE-FAILED").draftId()).isEqualTo(failedDraftId);
    assertThat(find(candidates, "CANDIDATE-FAILED").draftAnalysisStatus()).isEqualTo("AI_FAILED");
    assertThat(find(candidates, "CANDIDATE-FAILED").draftAnalysisTaskStatus()).isEqualTo("FAILED");
    assertThat(find(candidates, "CANDIDATE-DRAFT-ONLY").draftAnalysisStatus())
        .isEqualTo("AI_COMPLETED_PENDING_CONFIRMATION");
    assertThat(find(candidates, "CANDIDATE-DRAFT-ONLY").draftAnalysisTaskStatus()).isNull();
    assertThat(candidates).noneMatch(candidate -> candidate.billingPointCode().equals("CANDIDATE-GENERATED"));
    assertThat(noDraftSnapshot).isPositive();
  }

  private ReportGenerationService.Candidate find(
      java.util.List<ReportGenerationService.Candidate> candidates, String billingPointCode) {
    return candidates.stream()
        .filter(candidate -> candidate.billingPointCode().equals(billingPointCode))
        .findFirst()
        .orElseThrow();
  }

  private CurrentUser actor() {
    CurrentUser actor = mock(CurrentUser.class);
    when(actor.username()).thenReturn(CREATED_BY);
    when(actor.cityCode()).thenReturn("320100");
    when(actor.roles()).thenReturn(Set.of(Role.CITY_USER));
    return actor;
  }

  private long seedOverLimitSnapshot(String billingPointCode, String billingPointName, String period) {
    long importId = seedImportJob(period);
    String snapshotPublicId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO billing_point_snapshot
          (public_id, data_period, period_start, period_end, city_code,
           source_import_job_id, source_row_no, raw_row_json, billing_point_code,
           billing_point_name, city_name, district_name, data_json)
        VALUES (?, ?, ?, ?, '320100', ?, 1, '{}', ?, ?, '南京市', '鼓楼区', '{}')
        """,
        snapshotPublicId,
        period,
        period + "-01",
        period + "-28",
        importId,
        billingPointCode,
        billingPointName);
    jdbcTemplate.update(
        """
        INSERT INTO audit_result
          (public_id, billing_point_code, billing_point_name, city_code, data_period,
           period_start, period_end, audit_status, report_status, over_limit_type,
           max_ratio, yoy_result, yoy_ratio, detail_json)
        VALUES (?, ?, ?, '320100', ?, ?, ?, 'OVER_LIMIT', 'WAITING',
                'ONLY_YOY', 21.5, 'OVER_LIMIT', 21.5, '{}')
        """,
        UUID.randomUUID().toString(),
        billingPointCode,
        billingPointName,
        period,
        period + "-01",
        period + "-28");
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM billing_point_snapshot WHERE public_id = ?",
            Long.class,
            snapshotPublicId);
    if (id == null) throw new IllegalStateException("Snapshot was not inserted");
    return id;
  }

  private String seedDraft(
      long snapshotId, String billingPointCode, String billingPointName, String analysisStatus) {
    return seedDraft(snapshotId, billingPointCode, billingPointName, analysisStatus, null);
  }

  private String seedDraft(
      long snapshotId,
      String billingPointCode,
      String billingPointName,
      String analysisStatus,
      String taskPublicId) {
    String draftPublicId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO report_draft
          (public_id, billing_point_code, billing_point_name, data_period,
           period_start, period_end, billing_point_snapshot_id, status, title,
           situation, analysis, rectification, current_version_no,
           current_image_file_ids_json, analysis_status, analysis_task_public_id,
           created_by, updated_by)
        VALUES (?, ?, ?, '2026-04', '2026-04-01', '2026-04-28', ?, 'DRAFT',
                ?, '情况', '分析', '整改', 0, '[]', ?, ?, ?, ?)
        """,
        draftPublicId,
        billingPointCode,
        billingPointName,
        snapshotId,
        billingPointName + "电费稽核说明",
        analysisStatus,
        taskPublicId,
        CREATED_BY,
        CREATED_BY);
    return draftPublicId;
  }

  private void seedTask(String taskPublicId, String status) {
    jdbcTemplate.update(
        """
        INSERT INTO business_task
          (public_id, task_type, business_key, status, attempts, max_attempts,
           next_run_at, payload_json, created_by, updated_by)
        VALUES (?, 'AI_IMAGE_ANALYSIS', ?, ?, 0, 3, CURRENT_TIMESTAMP(3), '{}', ?, ?)
        """,
        taskPublicId,
        "AI_IMAGE_ANALYSIS:CANDIDATE-QUEUED:" + taskPublicId,
        status,
        CREATED_BY,
        CREATED_BY);
  }

  private void seedFormalReport(long snapshotId) {
    long wordFileId = seedStoredFile("generated.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    long pdfFileId = seedStoredFile("generated.pdf", "application/pdf");
    jdbcTemplate.update(
        """
        INSERT INTO audit_report
          (public_id, report_number, billing_point_snapshot_id, source_type, status,
           title, situation, analysis, rectification, word_file_id, pdf_file_id,
           business_snapshot_json, updated_by)
        VALUES (?, ?, ?, 'GENERATED', 'GENERATED', '已生成报告',
                '情况', '分析', '整改', ?, ?, '{}', ?)
        """,
        UUID.randomUUID().toString(),
        "TEST-" + UUID.randomUUID().toString().substring(0, 24),
        snapshotId,
        wordFileId,
        pdfFileId,
        CREATED_BY);
  }

  private long seedImportJob(String period) {
    long storedFileId = seedStoredFile("seed-" + period + ".csv", "text/csv");
    String importPublicId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO import_job
          (public_id, dataset_type, data_period, city_code, status, source_file_id,
           task_public_id, errors_json, created_by, updated_by)
        VALUES (?, 'BILLING_POINT', ?, '320100', 'SUCCEEDED', ?, ?, '[]', ?, ?)
        """,
        importPublicId,
        period,
        storedFileId,
        UUID.randomUUID().toString(),
        CREATED_BY,
        CREATED_BY);
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM import_job WHERE public_id = ?",
            Long.class,
            importPublicId);
    if (id == null) throw new IllegalStateException("Import job was not inserted");
    return id;
  }

  private long seedStoredFile(String originalName, String mediaType) {
    String publicId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO stored_file
          (public_id, storage_name, original_name, media_type, byte_size, sha256,
           purpose, created_by)
        VALUES (?, ?, ?, ?, 1, ?, 'TEST', ?)
        """,
        publicId,
        publicId + "-" + originalName,
        originalName,
        mediaType,
        publicId.replace("-", "").substring(0, 32).repeat(2),
        CREATED_BY);
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM stored_file WHERE public_id = ?",
            Long.class,
            publicId);
    if (id == null) throw new IllegalStateException("Stored file was not inserted");
    return id;
  }
}
