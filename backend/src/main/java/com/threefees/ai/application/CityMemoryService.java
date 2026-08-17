package com.threefees.ai.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists only confirmed reports and explicit user corrections as durable city memory. */
@Service
public class CityMemoryService {

  private final JdbcTemplate jdbcTemplate;

  public CityMemoryService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Transactional
  public void confirmGeneratedReport(String draftPublicId, String reportPublicId, String actor) {
    ReportMemorySource source = source(reportPublicId, draftPublicId);
    upsertHistoricalCase(source, "CONFIRMED_REPORT");
    if (source.finalReason() == null || source.finalReason().isBlank()) {
      return;
    }
    Integer exists =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ai_city_memory WHERE source_report_id = ?",
            Integer.class,
            source.reportId());
    if (exists != null && exists > 0) {
      return;
    }
    jdbcTemplate.update(
        """
        INSERT INTO ai_city_memory
          (public_id, city_code, billing_point_code, over_limit_type,
           initial_reason, user_correction, final_reason, evidence_summary,
           rectification_summary, trust_level, source_report_id, source_message_id,
           confirmed_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED_REPORT', ?, ?, ?)
        """,
        UUID.randomUUID().toString(),
        source.cityCode(),
        source.billingPointCode(),
        source.overLimitType(),
        blankToNull(source.initialReason()),
        blankToNull(source.userCorrection()),
        source.finalReason(),
        compact(source.analysis(), 12_000),
        compact(source.rectification(), 8000),
        source.reportId(),
        source.messageId(),
        actor);
  }

  @Transactional
  public void indexHistoricalReport(String reportPublicId) {
    upsertHistoricalCase(source(reportPublicId, null), "IMPORTED_REPORT");
  }

  @Transactional
  public void updateHistoricalImageAnalysis(
      String reportPublicId,
      int imageCount,
      String status,
      String analysisText,
      String errorCode) {
    jdbcTemplate.update(
        """
        UPDATE historical_audit_case
           SET image_count=?, image_analysis_status=?, image_analysis_text=?,
               image_analysis_error_code=?, updated_at=CURRENT_TIMESTAMP(3)
         WHERE report_id=(SELECT id FROM audit_report WHERE public_id=?)
        """,
        imageCount,
        status,
        blankToNull(analysisText),
        blankToNull(errorCode),
        reportPublicId);
  }

  private void upsertHistoricalCase(ReportMemorySource source, String trustLevel) {
    String summary =
        compact(
            "标题="
                + source.title()
                + "；情况="
                + source.situation()
                + "；分析="
                + source.analysis()
                + "；整改="
                + source.rectification(),
            24_000);
    jdbcTemplate.update(
        """
        INSERT INTO historical_audit_case
          (public_id, report_id, city_code, billing_point_code, data_period,
           over_limit_type, final_reason, summary, trust_level)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE final_reason=VALUES(final_reason), summary=VALUES(summary),
                                trust_level=VALUES(trust_level), updated_at=CURRENT_TIMESTAMP(3)
        """,
        UUID.randomUUID().toString(),
        source.reportId(),
        source.cityCode(),
        source.billingPointCode(),
        source.period(),
        source.overLimitType(),
        blankToNull(source.finalReason()),
        summary,
        trustLevel);
  }

  private ReportMemorySource source(String reportPublicId, String draftPublicId) {
    String draftJoin =
        draftPublicId == null
            ? "LEFT JOIN report_draft d ON 1=0"
            : "LEFT JOIN report_draft d ON d.public_id = ?";
    var args = new java.util.ArrayList<Object>();
    if (draftPublicId != null) {
      args.add(draftPublicId);
    }
    args.add(reportPublicId);
    return jdbcTemplate
        .query(
            """
            SELECT r.id AS report_id, r.title, r.situation, r.analysis, r.rectification,
                   s.city_code, s.billing_point_code, s.data_period, a.over_limit_type,
                   d.ai_initial_reason, d.ai_final_reason,
                   (SELECT m.id FROM report_draft_message m
                     WHERE m.draft_id=d.id AND m.intent='CORRECTION'
                     ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS correction_message_id,
                   (SELECT m.user_content FROM report_draft_message m
                     WHERE m.draft_id=d.id AND m.intent='CORRECTION'
                     ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS user_correction
              FROM audit_report r
              JOIN billing_point_snapshot s ON s.id=r.billing_point_snapshot_id
              LEFT JOIN audit_result a
                ON a.billing_point_code=s.billing_point_code
               AND a.data_period=s.data_period
               AND a.city_code=s.city_code
            """
                + draftJoin
                + " WHERE r.public_id = ?",
            (rs, row) ->
                new ReportMemorySource(
                    rs.getLong("report_id"),
                    rs.getString("city_code"),
                    rs.getString("billing_point_code"),
                    rs.getString("data_period"),
                    rs.getString("over_limit_type"),
                    rs.getString("title"),
                    rs.getString("situation"),
                    rs.getString("analysis"),
                    rs.getString("rectification"),
                    rs.getString("ai_initial_reason"),
                    rs.getString("ai_final_reason"),
                    rs.getObject("correction_message_id", Long.class),
                    rs.getString("user_correction")),
            args.toArray())
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("Formal report does not exist: " + reportPublicId));
  }

  private String compact(String value, int maxLength) {
    if (value == null) {
      return "";
    }
    String normalized = value.replaceAll("(?is)data:image/[^;]+;base64,[A-Za-z0-9+/=]+", "[报告图片]");
    return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private record ReportMemorySource(
      long reportId,
      String cityCode,
      String billingPointCode,
      String period,
      String overLimitType,
      String title,
      String situation,
      String analysis,
      String rectification,
      String initialReason,
      String finalReason,
      Long messageId,
      String userCorrection) {}
}
