package com.threefees.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {"three-fees.process-role=api", "app.bootstrap.enabled=false"})
class AuditReportServiceReportNumberTest {

  private static final String CREATED_BY = "report-number-unique-test";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AuditReportService reportService;

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("DELETE FROM audit_report WHERE updated_by = ?", CREATED_BY);
    jdbcTemplate.update(
        "DELETE FROM audit_result WHERE billing_point_code LIKE 'REPORT-NUMBER-%'");
    jdbcTemplate.update(
        "DELETE FROM billing_point_snapshot WHERE billing_point_code LIKE 'REPORT-NUMBER-%'");
    jdbcTemplate.update("DELETE FROM import_job WHERE created_by = ?", CREATED_BY);
    jdbcTemplate.update("DELETE FROM stored_file WHERE created_by = ?", CREATED_BY);
  }

  @Test
  void reportNumberIsUniqueAcrossGeneratedAndImportedReports() {
    long firstSnapshot = seedSnapshot("REPORT-NUMBER-GENERATED", "系统生成编号测试", "2026-05");
    long secondSnapshot = seedSnapshot("REPORT-NUMBER-IMPORTED", "历史导入编号测试", "2026-05");
    long wordFileId =
        seedStoredFile(
            "report.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    long pdfFileId = seedStoredFile("report.pdf", "application/pdf");

    seedReport(firstSnapshot, "BG-202605-000001", "GENERATED", wordFileId, pdfFileId);

    assertThatThrownBy(
            () ->
                seedReport(
                    secondSnapshot, "BG-202605-000001", "IMPORTED", wordFileId, pdfFileId))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void generatedAndImportedReportsShareTheSameArchiveDirectory() {
    long generatedSnapshot = seedSnapshot("REPORT-NUMBER-LIST-G", "系统生成目录测试", "2026-06");
    long importedSnapshot = seedSnapshot("REPORT-NUMBER-LIST-I", "历史导入目录测试", "2026-06");
    long wordFileId =
        seedStoredFile(
            "list.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    long pdfFileId = seedStoredFile("list.pdf", "application/pdf");
    seedReport(generatedSnapshot, "BG-202606-000001", "GENERATED", wordFileId, pdfFileId);
    seedReport(importedSnapshot, "BG-202606-000002", "IMPORTED", wordFileId, pdfFileId);

    var reportNumbers =
        jdbcTemplate.queryForList(
            """
            SELECT report_number
              FROM audit_report
             WHERE updated_by = ?
             ORDER BY report_number
            """,
            String.class,
            CREATED_BY);
    var sourceTypes =
        jdbcTemplate.queryForList(
            """
            SELECT source_type
              FROM audit_report
             WHERE updated_by = ?
             ORDER BY report_number
            """,
            String.class,
            CREATED_BY);

    assertThat(reportNumbers)
        .contains("BG-202606-000001", "BG-202606-000002")
        .doesNotHaveDuplicates();
    assertThat(sourceTypes).contains("GENERATED", "IMPORTED");
  }

  @Test
  void nextReportNumberSkipsExistingReportsWhenSequenceIsBehindOldData() {
    long snapshot = seedSnapshot("REPORT-NUMBER-SEQUENCE", "流水落后测试", "2026-07");
    long wordFileId =
        seedStoredFile(
            "sequence.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    long pdfFileId = seedStoredFile("sequence.pdf", "application/pdf");
    seedReport(snapshot, "BG-202607-000001", "GENERATED", wordFileId, pdfFileId);
    jdbcTemplate.update(
        """
        MERGE INTO report_number_sequence (business_month, next_value, version)
        KEY (business_month)
        VALUES ('202607', 1, 0)
        """);

    String next =
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
            reportService, "nextReportNumber", "2026-07");

    assertThat(next).isEqualTo("BG-202607-000002");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT next_value FROM report_number_sequence WHERE business_month = '202607'",
                Long.class))
        .isEqualTo(3L);
  }

  private long seedSnapshot(String billingPointCode, String billingPointName, String period) {
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
        period + "-30",
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
        period + "-30");
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM billing_point_snapshot WHERE public_id = ?",
            Long.class,
            snapshotPublicId);
    if (id == null) throw new IllegalStateException("Snapshot was not inserted");
    return id;
  }

  private void seedReport(
      long snapshotId, String reportNumber, String sourceType, long wordFileId, long pdfFileId) {
    jdbcTemplate.update(
        """
        INSERT INTO audit_report
          (public_id, report_number, billing_point_snapshot_id, source_type, status,
           title, situation, analysis, rectification, word_file_id, pdf_file_id,
           business_snapshot_json, updated_by)
        VALUES (?, ?, ?, ?, 'GENERATED', '报告编号唯一性测试',
                '情况', '分析', '整改', ?, ?, '{}', ?)
        """,
        UUID.randomUUID().toString(),
        reportNumber,
        snapshotId,
        sourceType,
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
