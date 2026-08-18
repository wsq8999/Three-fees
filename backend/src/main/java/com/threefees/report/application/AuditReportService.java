package com.threefees.report.application;

import com.threefees.ai.application.AiServiceClient.ReportSections;
import com.threefees.file.application.StoredFileService;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.application.ResourceConflictException;
import com.threefees.identity.application.ResourceNotFoundException;
import com.threefees.identity.domain.Role;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuditReportService {

  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
  private static final DateTimeFormatter NUMBER_MONTH = DateTimeFormatter.ofPattern("yyyyMM");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final StoredFileService storedFileService;

  public AuditReportService(
      JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, StoredFileService storedFileService) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.storedFileService = storedFileService;
  }

  @Transactional(readOnly = true)
  public GenerationInput generationInput(String draftPublicId) {
    return jdbcTemplate
        .query(
            """
            SELECT d.id AS draft_db_id, d.public_id AS draft_public_id, d.status AS draft_status,
                   d.current_version_no, d.title, d.situation, d.analysis, d.rectification,
                   d.current_image_file_ids_json, d.formal_report_public_id, d.ai_final_reason,
                   s.id AS snapshot_db_id, s.public_id AS snapshot_public_id,
                   s.billing_point_code, s.billing_point_name, s.city_code, s.data_period,
                   s.data_json AS snapshot_json,
                   a.detail_json AS audit_json, a.audit_status, a.over_limit_type,
                   a.max_ratio, a.actual_energy, a.actual_amount
              FROM report_draft d
              JOIN billing_point_snapshot s ON s.id = d.billing_point_snapshot_id
              LEFT JOIN audit_result a
                ON a.billing_point_code = s.billing_point_code AND a.data_period = s.data_period AND a.city_code = s.city_code
             WHERE d.public_id = ?
            """,
            (resultSet, rowNumber) ->
                new GenerationInput(
                    resultSet.getLong("draft_db_id"),
                    resultSet.getString("draft_public_id"),
                    resultSet.getString("draft_status"),
                    resultSet.getInt("current_version_no"),
                    resultSet.getString("formal_report_public_id"),
                    resultSet.getString("ai_final_reason"),
                    resultSet.getLong("snapshot_db_id"),
                    resultSet.getString("snapshot_public_id"),
                    resultSet.getString("billing_point_code"),
                    resultSet.getString("billing_point_name"),
                    resultSet.getString("city_code"),
                    resultSet.getString("data_period"),
                    new ReportSections(
                        resultSet.getString("title"),
                        resultSet.getString("situation"),
                        resultSet.getString("analysis"),
                        resultSet.getString("rectification")),
                    readList(resultSet.getString("current_image_file_ids_json")),
                    parseJson(resultSet.getString("snapshot_json")),
                    parseJson(resultSet.getString("audit_json")),
                    resultSet.getString("audit_status"),
                    resultSet.getString("over_limit_type"),
                    resultSet.getBigDecimal("max_ratio"),
                    resultSet.getBigDecimal("actual_energy"),
                    resultSet.getBigDecimal("actual_amount")),
            draftPublicId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("报告工作稿"));
  }

  @Transactional(readOnly = true)
  public String existingReportForSnapshot(long snapshotId) {
    List<String> ids =
        jdbcTemplate.queryForList(
            "SELECT public_id FROM audit_report WHERE billing_point_snapshot_id = ?",
            String.class,
            snapshotId);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  @Transactional
  public void reconcileDraftWithExistingReport(long draftId, String reportId, String actor) {
    jdbcTemplate.update(
        """
        UPDATE report_draft
           SET status='FORMALIZED', formal_report_public_id=?,
               updated_at=CURRENT_TIMESTAMP(3), updated_by=?, version=version+1
         WHERE id=? AND (formal_report_public_id IS NULL OR formal_report_public_id=?)
        """,
        reportId,
        actor,
        draftId,
        reportId);
  }

  @Transactional
  public void reconcileHistoricalImportWithExistingReport(
      long historicalImportId, String reportId, String actor) {
    jdbcTemplate.update(
        """
        UPDATE historical_report_import
           SET status='SUCCEEDED', report_public_id=?, error_code=NULL,
               updated_at=CURRENT_TIMESTAMP(3), updated_by=?, version=version+1
         WHERE id=?
        """,
        reportId,
        actor,
        historicalImportId);
  }

  @Transactional
  public FinalizationResult finalizeSystemReport(
      GenerationInput input, long wordFileId, long pdfFileId, String actor) {
    jdbcTemplate.queryForObject(
        "SELECT version FROM report_draft WHERE id = ? FOR UPDATE", Long.class, input.draftId());
    String existing = existingReportForSnapshot(input.snapshotId());
    if (existing != null) {
      return new FinalizationResult(existing, false);
    }
    if (!"GENERATING"
        .equals(
            jdbcTemplate.queryForObject(
                "SELECT status FROM report_draft WHERE id = ?", String.class, input.draftId()))) {
      throw new ResourceConflictException("DRAFT_NOT_GENERATING", "工作稿不在正式报告生成状态");
    }
    String publicId = UUID.randomUUID().toString();
    String reportNumber = nextReportNumber(input.period());
    String snapshot =
        writeJson(
            Map.of(
                "snapshotId", input.snapshotPublicId(),
                "billingPointCode", input.billingPointCode(),
                "billingPointName", input.billingPointName(),
                "cityCode", input.cityCode(),
                "period", input.period(),
                "billingPoint", input.snapshotJson(),
                "audit", input.auditJson(),
                "sections", input.sections(),
                "imageFileIds", input.imageFileIds()));
    try {
      jdbcTemplate.update(
          """
          INSERT INTO audit_report
            (public_id, report_number, billing_point_snapshot_id, source_type, status,
             title, situation, analysis, rectification, word_file_id, pdf_file_id,
             business_snapshot_json, updated_by)
          VALUES (?, ?, ?, 'GENERATED', 'GENERATED', ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          publicId,
          reportNumber,
          input.snapshotId(),
          input.sections().title(),
          input.sections().situation(),
          input.sections().analysis(),
          input.sections().rectification(),
          wordFileId,
          pdfFileId,
          snapshot,
          actor);
    } catch (DuplicateKeyException exception) {
      String concurrent = existingReportForSnapshot(input.snapshotId());
      if (concurrent != null) {
        return new FinalizationResult(concurrent, false);
      }
      throw exception;
    }
    jdbcTemplate.update(
        """
        UPDATE report_draft
           SET status = 'FORMALIZED', formal_report_public_id = ?,
               updated_at = CURRENT_TIMESTAMP(3), updated_by = ?, version = version + 1
         WHERE id = ? AND status = 'GENERATING'
        """,
        publicId,
        actor,
        input.draftId());
    return new FinalizationResult(publicId, true);
  }

  @Transactional
  public FinalizationResult finalizeCorrectionReport(
      GenerationInput input, long wordFileId, long pdfFileId, String actor) {
    if (input.formalReportId() == null || input.formalReportId().isBlank()) {
      throw new ResourceConflictException("CORRECTION_REPORT_MISSING", "更正工作稿未关联正式报告");
    }
    jdbcTemplate.queryForObject(
        "SELECT version FROM report_draft WHERE id = ? FOR UPDATE", Long.class, input.draftId());
    if (!"GENERATING"
        .equals(
            jdbcTemplate.queryForObject(
                "SELECT status FROM report_draft WHERE id = ?", String.class, input.draftId()))) {
      throw new ResourceConflictException("DRAFT_NOT_GENERATING", "工作稿不在正式报告生成状态");
    }
    String snapshot =
        writeJson(
            Map.of(
                "snapshotId", input.snapshotPublicId(),
                "billingPointCode", input.billingPointCode(),
                "billingPointName", input.billingPointName(),
                "cityCode", input.cityCode(),
                "period", input.period(),
                "billingPoint", input.snapshotJson(),
                "audit", input.auditJson(),
                "sections", input.sections(),
                "imageFileIds", input.imageFileIds()));
    int updated =
        jdbcTemplate.update(
            """
            UPDATE audit_report
               SET status = 'CORRECTED',
                   title = ?, situation = ?, analysis = ?, rectification = ?,
                   word_file_id = ?, pdf_file_id = ?, business_snapshot_json = ?,
                   correction_reason = ?,
                   corrected_at = CURRENT_TIMESTAMP(3), corrected_by = ?,
                   updated_at = CURRENT_TIMESTAMP(3), updated_by = ?, version = version + 1
             WHERE public_id = ?
            """,
            input.sections().title(),
            input.sections().situation(),
            input.sections().analysis(),
            input.sections().rectification(),
            wordFileId,
            pdfFileId,
            snapshot,
            valueOr(input.correctionReason(), "报告内容更正"),
            actor,
            actor,
            input.formalReportId());
    if (updated != 1) {
      throw new ResourceNotFoundException("正式报告");
    }
    jdbcTemplate.update(
        """
        UPDATE report_draft
           SET status = 'FORMALIZED', formal_report_public_id = ?,
               updated_at = CURRENT_TIMESTAMP(3), updated_by = ?, version = version + 1
         WHERE id = ? AND status = 'GENERATING'
        """,
        input.formalReportId(),
        actor,
        input.draftId());
    return new FinalizationResult(input.formalReportId(), true);
  }

  @Transactional
  public void resetFailedGeneration(String draftPublicId, String actor) {
    jdbcTemplate.update(
        """
        UPDATE report_draft
           SET status = CASE WHEN formal_report_public_id IS NULL THEN 'DRAFT' ELSE 'CORRECTING' END,
               updated_at = CURRENT_TIMESTAMP(3), updated_by = ?,
               version = version + 1
         WHERE public_id = ? AND status = 'GENERATING'
        """,
        actor,
        draftPublicId);
  }

  @Transactional
  public void beginOrResumeGeneration(String draftPublicId, String actor) {
    int matched =
        jdbcTemplate.update(
            """
            UPDATE report_draft
               SET status='GENERATING', updated_at=CURRENT_TIMESTAMP(3), updated_by=?,
                   version=CASE WHEN status IN ('DRAFT','CORRECTING') THEN version+1 ELSE version END
             WHERE public_id=? AND status IN ('DRAFT','CORRECTING','GENERATING')
            """,
            actor,
            draftPublicId);
    if (matched != 1) {
      throw new ResourceConflictException("DRAFT_GENERATION_STATE_INVALID", "工作稿当前状态不能生成正式报告");
    }
  }

  @Transactional(readOnly = true)
  public ReportPage findPage(
      String keyword,
      String period,
      String cityCode,
      String district,
      int page,
      int size,
      CurrentUser actor) {
    String scopedCity = scopeCity(actor, cityCode);
    var arguments = new java.util.ArrayList<Object>();
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    if (scopedCity != null && !scopedCity.isBlank()) {
      where.append(" AND s.city_code = ?");
      arguments.add(scopedCity);
    }
      if (district != null && !district.isBlank()) {
          where.append(
              """
               AND COALESCE(
                 NULLIF(JSON_UNQUOTE(JSON_EXTRACT(s.data_json, '$."所属区县"')), ''),
                 NULLIF(JSON_UNQUOTE(JSON_EXTRACT(s.data_json, '$."区县"')), ''),
                 NULLIF(JSON_UNQUOTE(JSON_EXTRACT(s.data_json, '$."行政区"')), ''),
                 NULLIF(JSON_UNQUOTE(JSON_EXTRACT(s.data_json, '$."所属区域"')), '')
               ) = ?
              """);

          arguments.add(district.trim());
      }
    if (period != null && !period.isBlank()) {
      where.append(" AND s.data_period = ?");
      arguments.add(period);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(
          " AND (r.report_number LIKE ? OR s.billing_point_code LIKE ? OR s.billing_point_name LIKE ?)");
      String pattern = "%" + keyword.trim() + "%";
      arguments.add(pattern);
      arguments.add(pattern);
      arguments.add(pattern);
    }
    long total =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_report r JOIN billing_point_snapshot s ON s.id = r.billing_point_snapshot_id"
                + where,
            Long.class,
            arguments.toArray());
    var pageArguments = new java.util.ArrayList<>(arguments);
    pageArguments.add(size);
    pageArguments.add(Math.multiplyExact(page, size));
    List<ReportSummary> items =
        jdbcTemplate.query(
            """
            SELECT r.public_id, r.report_number, r.source_type, r.status, r.generated_at,
                   r.updated_at, r.version, s.billing_point_code, s.billing_point_name,
                   s.city_code, c.name AS city_name, s.data_period, s.data_json,
                   COALESCE(
                     (SELECT SUM(m.allocated_kwh)
                        FROM meter_reading m
                       WHERE m.billing_point_code = s.billing_point_code
                         AND m.data_period = s.data_period
                         AND m.city_code = s.city_code),
                     a.actual_energy
                   ) AS actual_energy,
                   COALESCE(
                     (SELECT SUM(p.actual_report_amount)
                        FROM payment_detail p
                       WHERE p.billing_point_code = s.billing_point_code
                         AND p.data_period = s.data_period
                         AND p.city_code = s.city_code
                         AND p.id IN (
                           SELECT MIN(p2.id)
                             FROM payment_detail p2
                            WHERE p2.billing_point_code = s.billing_point_code
                              AND p2.data_period = s.data_period
                              AND p2.city_code = s.city_code
                            GROUP BY p2.payment_bill_code
                         )),
                     a.actual_amount
                   ) AS actual_amount,
                   a.over_limit_type, a.max_ratio,
                   JSON_UNQUOTE(JSON_EXTRACT(r.business_snapshot_json, '$.audit.overLimitType')) AS snapshot_over_limit_type,
                   JSON_UNQUOTE(JSON_EXTRACT(r.business_snapshot_json, '$.audit.result.overLimitType')) AS snapshot_result_over_limit_type,
                   JSON_UNQUOTE(JSON_EXTRACT(r.business_snapshot_json, '$.audit.maxRatioPercent')) AS snapshot_max_ratio,
                   JSON_UNQUOTE(JSON_EXTRACT(r.business_snapshot_json, '$.audit.result.maxRatioPercent')) AS snapshot_result_max_ratio,
                   JSON_UNQUOTE(JSON_EXTRACT(r.business_snapshot_json, '$.audit.currentActualEnergy')) AS snapshot_actual_energy,
                   JSON_UNQUOTE(JSON_EXTRACT(r.business_snapshot_json, '$.audit.result.actualEnergy')) AS snapshot_result_actual_energy,
                   JSON_UNQUOTE(JSON_EXTRACT(r.business_snapshot_json, '$.audit.currentActualAmount')) AS snapshot_actual_amount
              FROM audit_report r
              JOIN billing_point_snapshot s ON s.id = r.billing_point_snapshot_id
              JOIN city c ON c.code = s.city_code
              LEFT JOIN audit_result a
                ON a.billing_point_code = s.billing_point_code AND a.data_period = s.data_period AND a.city_code = s.city_code
            """
                + where
                + " ORDER BY r.generated_at DESC, r.id DESC LIMIT ? OFFSET ?",
            (resultSet, rowNumber) ->
                new ReportSummary(
                    resultSet.getString("public_id"),
                    resultSet.getString("report_number"),
                    resultSet.getString("billing_point_code"),
                    resultSet.getString("billing_point_name"),
                    resultSet.getString("city_code"),
                    resultSet.getString("city_name"),
                    district(parseJson(resultSet.getString("data_json"))),
                    resultSet.getString("data_period"),
                    resultSet.getString("source_type"),
                    resultSet.getString("status"),
                    decimalOr(
                        decimalOr(
                            resultSet.getBigDecimal("actual_energy"),
                            resultSet.getString("snapshot_actual_energy")),
                        resultSet.getString("snapshot_result_actual_energy")),
                    decimalOr(
                        resultSet.getBigDecimal("actual_amount"),
                        resultSet.getString("snapshot_actual_amount")),
                    valueOr(
                        valueOr(
                            resultSet.getString("over_limit_type"),
                            resultSet.getString("snapshot_over_limit_type")),
                        resultSet.getString("snapshot_result_over_limit_type")),
                    decimalOr(
                        decimalOr(
                            resultSet.getBigDecimal("max_ratio"),
                            resultSet.getString("snapshot_max_ratio")),
                        resultSet.getString("snapshot_result_max_ratio")),
                    resultSet.getObject("generated_at", LocalDateTime.class),
                    resultSet.getObject("updated_at", LocalDateTime.class),
                    resultSet.getLong("version")),
            pageArguments.toArray());
    return new ReportPage(
        items, page, size, total, total == 0 ? 0 : (int) ((total + size - 1) / size));
  }

  @Transactional(readOnly = true)
  public ReportDetail find(String publicId, CurrentUser actor) {
    ReportDetail detail =
        jdbcTemplate
            .query(
                """
                SELECT r.public_id, r.report_number, r.source_type, r.status, r.title,
                       r.situation, r.analysis, r.rectification, r.business_snapshot_json,
                       r.generated_at, r.updated_at, r.updated_by, r.version,
                       r.correction_reason, r.corrected_at, r.corrected_by,
                       wf.public_id AS word_file_public_id, pf.public_id AS pdf_file_public_id,
                       s.public_id AS snapshot_public_id, s.billing_point_code,
                       s.billing_point_name, s.city_code, c.name AS city_name, s.data_period,
                       s.data_json,
                       COALESCE(
                         (SELECT SUM(m.allocated_kwh)
                            FROM meter_reading m
                           WHERE m.billing_point_code = s.billing_point_code
                             AND m.data_period = s.data_period
                             AND m.city_code = s.city_code),
                         a.actual_energy
                       ) AS actual_energy,
                       COALESCE(
                         (SELECT SUM(p.actual_report_amount)
                            FROM payment_detail p
                           WHERE p.billing_point_code = s.billing_point_code
                             AND p.data_period = s.data_period
                             AND p.city_code = s.city_code
                             AND p.id IN (
                               SELECT MIN(p2.id)
                                 FROM payment_detail p2
                                WHERE p2.billing_point_code = s.billing_point_code
                                  AND p2.data_period = s.data_period
                                  AND p2.city_code = s.city_code
                                GROUP BY p2.payment_bill_code
                             )),
                         a.actual_amount
                       ) AS actual_amount,
                       a.over_limit_type, a.max_ratio,
                       JSON_UNQUOTE(JSON_EXTRACT(r.business_snapshot_json, '$.audit.overLimitType')) AS snapshot_over_limit_type,
                       JSON_UNQUOTE(JSON_EXTRACT(r.business_snapshot_json, '$.audit.result.overLimitType')) AS snapshot_result_over_limit_type,
                       JSON_UNQUOTE(JSON_EXTRACT(r.business_snapshot_json, '$.audit.maxRatioPercent')) AS snapshot_max_ratio,
                       JSON_UNQUOTE(JSON_EXTRACT(r.business_snapshot_json, '$.audit.result.maxRatioPercent')) AS snapshot_result_max_ratio,
                       JSON_UNQUOTE(JSON_EXTRACT(r.business_snapshot_json, '$.audit.currentActualEnergy')) AS snapshot_actual_energy,
                       JSON_UNQUOTE(JSON_EXTRACT(r.business_snapshot_json, '$.audit.result.actualEnergy')) AS snapshot_result_actual_energy,
                       JSON_UNQUOTE(JSON_EXTRACT(r.business_snapshot_json, '$.audit.currentActualAmount')) AS snapshot_actual_amount
                  FROM audit_report r
                  JOIN billing_point_snapshot s ON s.id = r.billing_point_snapshot_id
                  JOIN city c ON c.code = s.city_code
                  JOIN stored_file wf ON wf.id = r.word_file_id
                  LEFT JOIN stored_file pf ON pf.id = r.pdf_file_id
                  LEFT JOIN audit_result a
                    ON a.billing_point_code = s.billing_point_code
                   AND a.data_period = s.data_period
                   AND a.city_code = s.city_code
                 WHERE r.public_id = ?
                """,
                (resultSet, rowNumber) ->
                    new ReportDetail(
                        resultSet.getString("public_id"),
                        resultSet.getString("report_number"),
                        resultSet.getString("snapshot_public_id"),
                        resultSet.getString("billing_point_code"),
                        resultSet.getString("billing_point_name"),
                        resultSet.getString("city_code"),
                        resultSet.getString("city_name"),
                        district(parseJson(resultSet.getString("data_json"))),
                        resultSet.getString("data_period"),
                        resultSet.getString("source_type"),
                        resultSet.getString("status"),
                        decimalOr(
                            decimalOr(
                                resultSet.getBigDecimal("actual_energy"),
                                resultSet.getString("snapshot_actual_energy")),
                            resultSet.getString("snapshot_result_actual_energy")),
                        decimalOr(
                            resultSet.getBigDecimal("actual_amount"),
                            resultSet.getString("snapshot_actual_amount")),
                        valueOr(
                            valueOr(
                                resultSet.getString("over_limit_type"),
                                resultSet.getString("snapshot_over_limit_type")),
                            resultSet.getString("snapshot_result_over_limit_type")),
                        decimalOr(
                            decimalOr(
                                resultSet.getBigDecimal("max_ratio"),
                                resultSet.getString("snapshot_max_ratio")),
                            resultSet.getString("snapshot_result_max_ratio")),
                        new ReportSections(
                            resultSet.getString("title"),
                            resultSet.getString("situation"),
                            resultSet.getString("analysis"),
                            resultSet.getString("rectification")),
                        resultSet.getString("word_file_public_id"),
                        resultSet.getString("pdf_file_public_id"),
                        parseJson(resultSet.getString("business_snapshot_json")),
                        resultSet.getObject("generated_at", LocalDateTime.class),
                        resultSet.getObject("updated_at", LocalDateTime.class),
                        resultSet.getString("updated_by"),
                        resultSet.getString("correction_reason"),
                        resultSet.getObject("corrected_at", LocalDateTime.class),
                        resultSet.getString("corrected_by"),
                        resultSet.getLong("version")),
                publicId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("正式报告"));
    requireScope(actor, detail.cityCode());
    return detail;
  }

  public FileAccess reportFile(String reportId, boolean pdf, CurrentUser actor) {
    ReportDetail report = find(reportId, actor);
    if (pdf && report.pdfFileId() == null) {
      throw new ResourceNotFoundException("PDF");
    }
    var file = storedFileService.find(pdf ? report.pdfFileId() : report.wordFileId());
    return new FileAccess(file, storedFileService.resource(file));
  }

  @Transactional(readOnly = true)
  public List<HistoricalCandidate> historicalCandidates(
      String codeKeyword, String cityCode, CurrentUser actor) {
    String scopedCity = scopeCity(actor, cityCode);
    var args = new java.util.ArrayList<Object>();
    StringBuilder where =
        new StringBuilder(
            """
             WHERE r.id IS NULL
            """);
    if (scopedCity != null && !scopedCity.isBlank()) {
      where.append(" AND s.city_code = ?");
      args.add(scopedCity);
    }
    if (codeKeyword != null && !codeKeyword.isBlank()) {
      where.append(" AND (s.billing_point_code LIKE ? OR s.billing_point_name LIKE ?)");
      String pattern = "%" + codeKeyword.trim() + "%";
      args.add(pattern);
      args.add(pattern);
    }
    return jdbcTemplate.query(
        """
        SELECT s.public_id, s.billing_point_code, s.billing_point_name, s.city_code,
               c.name AS city_name, s.data_period, a.over_limit_type, a.max_ratio
         FROM billing_point_snapshot s
         JOIN city c ON c.code = s.city_code
          LEFT JOIN audit_result a
            ON a.billing_point_code = s.billing_point_code AND a.data_period = s.data_period AND a.city_code = s.city_code
          LEFT JOIN audit_report r ON r.billing_point_snapshot_id = s.id
        """
            + where
            + " ORDER BY s.billing_point_code, s.data_period DESC LIMIT 500",
        (resultSet, rowNumber) ->
            new HistoricalCandidate(
                resultSet.getString("public_id"),
                resultSet.getString("billing_point_code"),
                resultSet.getString("billing_point_name"),
                resultSet.getString("city_code"),
                resultSet.getString("city_name"),
                resultSet.getString("data_period"),
                resultSet.getString("over_limit_type"),
                resultSet.getBigDecimal("max_ratio")),
        args.toArray());
  }

  @Transactional(readOnly = true)
  public List<HistoricalBillingPointOption> historicalBillingPoints(
      String keyword, String cityCode, CurrentUser actor) {
    String scopedCity = scopeCity(actor, cityCode);
    var args = new java.util.ArrayList<Object>();
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    if (scopedCity != null && !scopedCity.isBlank()) {
      where.append(" AND s.city_code = ?");
      args.add(scopedCity);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(" AND (s.billing_point_code LIKE ? OR s.billing_point_name LIKE ?)");
      String pattern = "%" + keyword.trim() + "%";
      args.add(pattern);
      args.add(pattern);
    }
    where.append(
        """
         AND EXISTS (
           SELECT 1
             FROM billing_point_snapshot sp
             JOIN audit_result ap
               ON ap.billing_point_code = sp.billing_point_code
              AND ap.data_period = sp.data_period
              AND ap.city_code = sp.city_code
             LEFT JOIN audit_report rp ON rp.billing_point_snapshot_id = sp.id
            WHERE sp.billing_point_code = s.billing_point_code
              AND sp.city_code = s.city_code
              AND ap.audit_status = 'OVER_LIMIT'
              AND ap.report_status = 'WAITING'
              AND rp.id IS NULL
         )
        """);
    return jdbcTemplate.query(
        """
        SELECT s.billing_point_code, MAX(s.billing_point_name) AS billing_point_name,
               s.city_code, MAX(c.name) AS city_name
          FROM billing_point_snapshot s
          JOIN city c ON c.code = s.city_code
        """
            + where
            + """
         GROUP BY s.billing_point_code, s.city_code
         ORDER BY s.billing_point_code, s.city_code
         LIMIT 500
        """,
        (resultSet, rowNumber) ->
            new HistoricalBillingPointOption(
                resultSet.getString("billing_point_code"),
                resultSet.getString("billing_point_name"),
                resultSet.getString("city_code"),
                resultSet.getString("city_name")),
        args.toArray());
  }

  @Transactional(readOnly = true)
  public List<HistoricalPeriodOption> historicalPeriods(
      String billingPointCode, String cityCode, CurrentUser actor) {
    String scopedCity = scopeCity(actor, cityCode);
    var args = new java.util.ArrayList<Object>();
    args.add(billingPointCode);
    StringBuilder where =
        new StringBuilder(
            """
             WHERE s.billing_point_code = ? AND r.id IS NULL
               AND a.audit_status = 'OVER_LIMIT'
               AND a.report_status = 'WAITING'
            """);
    if (scopedCity != null && !scopedCity.isBlank()) {
      where.append(" AND s.city_code = ?");
      args.add(scopedCity);
    }
    return jdbcTemplate.query(
        """
        SELECT s.public_id, s.data_period, a.over_limit_type, a.max_ratio
          FROM billing_point_snapshot s
          LEFT JOIN audit_report r ON r.billing_point_snapshot_id = s.id
          JOIN audit_result a
            ON a.billing_point_code = s.billing_point_code
           AND a.data_period = s.data_period
           AND a.city_code = s.city_code
        """
            + where
            + " ORDER BY s.data_period DESC",
        (resultSet, rowNumber) ->
            new HistoricalPeriodOption(
                resultSet.getString("public_id"),
                resultSet.getString("data_period"),
                resultSet.getString("over_limit_type"),
                resultSet.getBigDecimal("max_ratio")),
        args.toArray());
  }

  @Transactional
  public FinalizationResult finalizeHistoricalReport(
      long historicalImportId,
      long snapshotId,
      String title,
      String previewHtml,
      long sourceWordFileId,
      Long previewPdfFileId,
      String actor) {
    jdbcTemplate.queryForObject(
        "SELECT version FROM historical_report_import WHERE id = ? FOR UPDATE",
        Long.class,
        historicalImportId);
    String existing = existingReportForSnapshot(snapshotId);
    if (existing != null) {
      jdbcTemplate.update(
          """
          UPDATE historical_report_import
             SET status='SUCCEEDED', report_public_id=?,
                 updated_at=CURRENT_TIMESTAMP(3), updated_by=?, version=version+1
           WHERE id=?
          """,
          existing,
          actor,
          historicalImportId);
      return new FinalizationResult(existing, false);
    }
    SnapshotForHistory snapshot = snapshotForHistory(snapshotId);
    String publicId = UUID.randomUUID().toString();
    String reportNumber = nextReportNumber(snapshot.period());
    String situation = previewHtml;
    String analysis = "";
    String rectification = "";
    String businessSnapshot =
        writeJson(
            Map.of(
                "snapshotId", snapshot.publicId(),
                "billingPoint", snapshot.data(),
                "audit", snapshot.audit(),
                "historicalSource", true));
    jdbcTemplate.update(
        """
        INSERT INTO audit_report
          (public_id, report_number, billing_point_snapshot_id, source_type, status,
           title, situation, analysis, rectification, word_file_id, pdf_file_id,
           business_snapshot_json, updated_by)
        VALUES (?, ?, ?, 'IMPORTED', 'GENERATED', ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        publicId,
        reportNumber,
        snapshotId,
        title,
        situation,
        analysis,
        rectification,
        sourceWordFileId,
        previewPdfFileId,
        businessSnapshot,
        actor);
    jdbcTemplate.update(
        """
        UPDATE historical_report_import
           SET status='SUCCEEDED', report_public_id=?,
               updated_at=CURRENT_TIMESTAMP(3), updated_by=?, version=version+1
         WHERE id=?
        """,
        publicId,
        actor,
        historicalImportId);
    jdbcTemplate.update(
        """
        UPDATE audit_result a
          JOIN billing_point_snapshot s
            ON s.billing_point_code = a.billing_point_code
           AND s.data_period = a.data_period
           AND s.city_code = a.city_code
           SET a.report_status = 'GENERATED',
               a.updated_at = CURRENT_TIMESTAMP(3)
         WHERE s.id = ?
        """,
        snapshotId);
    return new FinalizationResult(publicId, true);
  }

  private SnapshotForHistory snapshotForHistory(long snapshotId) {
    return jdbcTemplate
        .query(
            """
            SELECT s.public_id, s.data_period, s.data_json, a.detail_json
              FROM billing_point_snapshot s
              LEFT JOIN audit_result a
                ON a.billing_point_code=s.billing_point_code
               AND a.data_period=s.data_period
               AND a.city_code=s.city_code
             WHERE s.id=?
            """,
            (rs, row) ->
                new SnapshotForHistory(
                    rs.getString("public_id"),
                    rs.getString("data_period"),
                    parseJson(rs.getString("data_json")),
                    parseJson(rs.getString("detail_json"))),
            snapshotId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("报账点账期"));
  }

  private String nextReportNumber(String reportPeriod) {
    String month = YearMonth.parse(reportPeriod).format(NUMBER_MONTH);
    try {
      jdbcTemplate.update(
          """
          INSERT INTO report_number_sequence (business_month, next_value, version)
          SELECT ?, 1, 0
           WHERE NOT EXISTS (
             SELECT 1 FROM report_number_sequence WHERE business_month = ?)
          """,
          month,
          month);
    } catch (DuplicateKeyException ignored) {
      // A concurrent transaction created the month row; the row lock below serializes allocation.
    }
    Long next =
        jdbcTemplate.queryForObject(
            "SELECT next_value FROM report_number_sequence WHERE business_month = ? FOR UPDATE",
            Long.class,
            month);
    if (next == null || next > 999_999L) {
      throw new IllegalStateException("Monthly report number sequence exhausted");
    }
    jdbcTemplate.update(
        "UPDATE report_number_sequence SET next_value = ?, version = version + 1 WHERE business_month = ?",
        next + 1,
        month);
    return "BG-" + month + "-" + String.format(java.util.Locale.ROOT, "%06d", next);
  }

  private String scopeCity(CurrentUser actor, String requestedCity) {
    if (!actor.roles().contains(Role.SUPER_ADMIN)) {
      if (requestedCity != null
          && !requestedCity.isBlank()
          && !actor.cityCode().equals(requestedCity)) {
        throw new AccessDeniedException("City scope mismatch");
      }
      return actor.cityCode();
    }
    return requestedCity;
  }

  private void requireScope(CurrentUser actor, String cityCode) {
    if (!actor.roles().contains(Role.SUPER_ADMIN) && !actor.cityCode().equals(cityCode)) {
      throw new AccessDeniedException("Report is outside city scope");
    }
  }

  private JsonNode parseJson(String value) {
    if (value == null || value.isBlank()) {
      return objectMapper.createObjectNode();
    }
    try {
      return objectMapper.readTree(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted report snapshot contains invalid JSON", exception);
    }
  }

  private List<String> readList(String value) {
    try {
      return objectMapper.readValue(value == null ? "[]" : value, STRING_LIST);
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Persisted report image list contains invalid JSON", exception);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Report snapshot could not be serialized", exception);
    }
  }

  private String valueOr(String value, String fallback) {
    return value == null || value.isBlank() || "null".equalsIgnoreCase(value) ? fallback : value;
  }

  private BigDecimal decimalOr(BigDecimal value, String fallback) {
    if (value != null) {
      return value;
    }
    if (fallback == null || fallback.isBlank() || "null".equalsIgnoreCase(fallback)) {
      return null;
    }
    try {
      return new BigDecimal(fallback);
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private String district(JsonNode values) {
    for (String field : List.of("所属区县", "区县", "行政区", "所属区域")) {
      String value = values.path(field).asText("").trim();
      if (!value.isEmpty()) {
        return value;
      }
    }
    return null;
  }

  public record GenerationInput(
      long draftId,
      String draftPublicId,
      String draftStatus,
      int contentVersion,
      String formalReportId,
      String correctionReason,
      long snapshotId,
      String snapshotPublicId,
      String billingPointCode,
      String billingPointName,
      String cityCode,
      String period,
      ReportSections sections,
      List<String> imageFileIds,
      JsonNode snapshotJson,
      JsonNode auditJson,
      String auditStatus,
      String overLimitType,
      BigDecimal maxRatio,
      BigDecimal actualEnergy,
      BigDecimal actualAmount) {}

  public record FinalizationResult(String reportId, boolean created) {}

  public record ReportSummary(
      String id,
      String reportNumber,
      String billingPointCode,
      String billingPointName,
      String cityCode,
      String cityName,
      String district,
      String period,
      String sourceType,
      String status,
      BigDecimal actualEnergy,
      BigDecimal actualAmount,
      String overLimitType,
      BigDecimal maxRatio,
      LocalDateTime generatedAt,
      LocalDateTime updatedAt,
      long version) {}

  public record ReportPage(
      List<ReportSummary> items, int page, int size, long total, int totalPages) {}

  public record ReportDetail(
      String id,
      String reportNumber,
      String billingPointPeriodId,
      String billingPointCode,
      String billingPointName,
      String cityCode,
      String cityName,
      String district,
      String period,
      String sourceType,
      String status,
      BigDecimal actualEnergy,
      BigDecimal actualAmount,
      String overLimitType,
      BigDecimal maxRatio,
      ReportSections sections,
      String wordFileId,
      String pdfFileId,
      JsonNode businessSnapshot,
      LocalDateTime generatedAt,
      LocalDateTime updatedAt,
      String updatedBy,
      String correctionReason,
      LocalDateTime correctedAt,
      String correctedBy,
      long version) {}

  public record HistoricalCandidate(
      String billingPointPeriodId,
      String billingPointCode,
      String billingPointName,
      String cityCode,
      String cityName,
      String period,
      String overLimitType,
      BigDecimal maxRatio) {}

  public record HistoricalBillingPointOption(
      String billingPointCode, String billingPointName, String cityCode, String cityName) {}

  public record HistoricalPeriodOption(
      String billingPointPeriodId, String period, String overLimitType, BigDecimal maxRatio) {}

  public record FileAccess(
      com.threefees.file.domain.StoredFile file,
      org.springframework.core.io.InputStreamResource resource) {}

  private record SnapshotForHistory(
      String publicId, String period, JsonNode data, JsonNode audit) {}
}
