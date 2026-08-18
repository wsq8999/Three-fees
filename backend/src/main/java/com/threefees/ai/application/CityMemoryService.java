package com.threefees.ai.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Persists only confirmed reports and explicit user corrections as durable city memory. */
@Service
public class CityMemoryService {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public CityMemoryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public void confirmGeneratedReport(String draftPublicId, String reportPublicId, String actor) {
    ReportMemorySource source = source(reportPublicId, draftPublicId);
    upsertHistoricalCase(source, "CONFIRMED_REPORT");
    if (source.finalReason() == null || source.finalReason().isBlank()) {
      rebuildPointProfile(source.cityCode(), source.billingPointCode());
      return;
    }
    long memoryId =
        upsertMemory(
            source.cityCode(),
            source.billingPointCode(),
            source.overLimitType(),
            source.period(),
            source.maxRatio(),
            source.initialReason(),
            source.userCorrection(),
            source.finalReason(),
            source.analysis(),
            source.rectification(),
            "CONFIRMED_REPORT",
            source.reportId(),
            source.messageId(),
            actor);
    deactivateDraftCorrections(draftPublicId, memoryId);
    rebuildPointProfile(source.cityCode(), source.billingPointCode());
  }

  @Transactional
  public void rememberUserCorrection(
      long draftId,
      long messageId,
      String userCorrection,
      String initialReason,
      String finalReason,
      String evidenceSummary,
      String rectificationSummary,
      String actor) {
    if (finalReason == null || finalReason.isBlank()) {
      return;
    }
    CorrectionMemorySource source =
        jdbcTemplate
            .query(
                """
                SELECT s.city_code, s.billing_point_code, s.data_period,
                       a.over_limit_type, a.max_ratio
                  FROM report_draft d
                  JOIN billing_point_snapshot s ON s.id = d.billing_point_snapshot_id
                  LEFT JOIN audit_result a
                    ON a.billing_point_code = s.billing_point_code
                   AND a.data_period = s.data_period
                   AND a.city_code = s.city_code
                 WHERE d.id = ?
                """,
                (rs, row) ->
                    new CorrectionMemorySource(
                        rs.getString("city_code"),
                        rs.getString("billing_point_code"),
                        rs.getString("data_period"),
                        rs.getString("over_limit_type"),
                        rs.getBigDecimal("max_ratio")),
                draftId)
            .stream()
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("Report draft does not exist: " + draftId));
    long memoryId =
        upsertMemory(
            source.cityCode(),
            source.billingPointCode(),
            source.overLimitType(),
            source.period(),
            source.maxRatio(),
            initialReason,
            userCorrection,
            finalReason,
            evidenceSummary,
            rectificationSummary,
            "USER_CONFIRMED",
            null,
            messageId,
            actor);
    supersedeOtherDraftCorrections(draftId, messageId, memoryId);
    rebuildPointProfile(source.cityCode(), source.billingPointCode());
  }

  @Transactional
  public void indexHistoricalReport(String reportPublicId) {
    ReportMemorySource source = source(reportPublicId, null);
    upsertHistoricalCase(source, "IMPORTED_REPORT");
    rebuildPointProfile(source.cityCode(), source.billingPointCode());
  }

  @Transactional
  public void updateHistoricalImageAnalysis(
      String reportPublicId, int imageCount, String status, String analysisText, String errorCode) {
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

  @Transactional
  public List<MemoryMatch> findRelevantMemories(MemoryQuery query, int requestedLimit) {
    if (query.cityCode() == null || query.cityCode().isBlank()) {
      throw new IllegalArgumentException("AI memory city scope is required");
    }
    int limit = Math.max(1, Math.min(requestedLimit, 10));
    int candidateLimit = Math.max(20, limit * 5);
    String season = season(query.period());
    String ratioBucket = ratioBucket(query.maxRatio());
    List<String> currentEvidenceTags = deriveEvidenceTags(query.evidenceText());
    List<MemoryMatch> matches =
        jdbcTemplate
            .query(
                """
            SELECT public_id, city_code, billing_point_code, over_limit_type,
                   final_reason, user_correction, evidence_summary, trust_level,
                   confirm_count, evidence_tags_json,
                   (CASE WHEN billing_point_code = ? THEN 40 ELSE 0 END
                    + CASE WHEN ? IS NOT NULL AND over_limit_type = ? THEN 20 ELSE 0 END
                    + CASE WHEN ? IS NOT NULL AND season_code = ? THEN 15 ELSE 0 END
                    + CASE WHEN ? IS NOT NULL AND ratio_bucket = ? THEN 10 ELSE 0 END
                    + CASE trust_level WHEN 'USER_CONFIRMED' THEN 20
                                       WHEN 'CONFIRMED_REPORT' THEN 12 ELSE 5 END
                    + CASE WHEN confirm_count >= 5 THEN 10 ELSE confirm_count * 2 END
                   ) AS relevance_score
              FROM ai_city_memory
             WHERE city_code = ? AND active = TRUE AND status = 'ACTIVE'
             ORDER BY relevance_score DESC, confirmed_at DESC, id DESC
             LIMIT ?
            """,
                (rs, row) ->
                    new MemoryMatch(
                        rs.getString("public_id"),
                        rs.getString("city_code"),
                        rs.getString("billing_point_code"),
                        rs.getString("over_limit_type"),
                        rs.getInt("relevance_score")
                            + evidenceTagScore(
                                rs.getString("evidence_tags_json"), currentEvidenceTags),
                        compact(
                            "可信度="
                                + rs.getString("trust_level")
                                + "；确认次数="
                                + rs.getInt("confirm_count")
                                + "；最终原因="
                                + value(rs.getString("final_reason"))
                                + "；用户纠正="
                                + value(rs.getString("user_correction"))
                                + "；证据="
                                + value(rs.getString("evidence_summary")),
                            2400)),
                query.billingPointCode(),
                query.overLimitType(),
                query.overLimitType(),
                season,
                season,
                ratioBucket,
                ratioBucket,
                query.cityCode(),
                candidateLimit)
            .stream()
            .sorted(
                java.util.Comparator.comparingInt(MemoryMatch::score)
                    .reversed()
                    .thenComparing(MemoryMatch::publicId))
            .limit(limit)
            .toList();
    for (MemoryMatch match : matches) {
      if (!query.cityCode().equals(match.cityCode())) {
        throw new IllegalStateException("Cross-city AI memory retrieval was blocked");
      }
      jdbcTemplate.update(
          """
          UPDATE ai_city_memory
             SET hit_count = hit_count + 1, last_used_at = CURRENT_TIMESTAMP(3)
           WHERE public_id = ? AND city_code = ?
          """,
          match.publicId(),
          query.cityCode());
    }
    return List.copyOf(matches);
  }

  public PointProfile findPointProfile(String cityCode, String billingPointCode) {
    PointProfile profile = loadPointProfile(cityCode, billingPointCode);
    if (profile != null) {
      return profile;
    }
    rebuildPointProfile(cityCode, billingPointCode);
    return loadPointProfile(cityCode, billingPointCode);
  }

  private PointProfile loadPointProfile(String cityCode, String billingPointCode) {
    return jdbcTemplate
        .query(
            """
            SELECT public_id, city_code, billing_point_code, historical_case_count,
                   active_memory_count, profile_summary
              FROM ai_billing_point_memory_profile
             WHERE city_code = ? AND billing_point_code = ?
            """,
            (rs, row) ->
                new PointProfile(
                    rs.getString("public_id"),
                    rs.getString("city_code"),
                    rs.getString("billing_point_code"),
                    rs.getInt("historical_case_count"),
                    rs.getInt("active_memory_count"),
                    rs.getString("profile_summary")),
            cityCode,
            billingPointCode)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private long upsertMemory(
      String cityCode,
      String billingPointCode,
      String overLimitType,
      String period,
      BigDecimal maxRatio,
      String initialReason,
      String userCorrection,
      String finalReason,
      String evidenceSummary,
      String rectificationSummary,
      String trustLevel,
      Long sourceReportId,
      Long sourceMessageId,
      String actor) {
    String safeFinalReason = compact(finalReason, 1000);
    String season = season(period);
    String ratioBucket = ratioBucket(maxRatio);
    String fingerprint =
        fingerprint(
            cityCode, billingPointCode, overLimitType, season, ratioBucket, safeFinalReason);
    ExistingMemory existing =
        findExistingMemory(cityCode, billingPointCode, safeFinalReason, fingerprint);
    String tagsJson = json(deriveEvidenceTags(safeFinalReason + " " + value(evidenceSummary)));
    if (existing != null) {
      if ((sourceReportId != null && sourceReportId.equals(existing.sourceReportId()))
          || (sourceMessageId != null && sourceMessageId.equals(existing.sourceMessageId()))) {
        return existing.id();
      }
      String effectiveTrust =
          "USER_CONFIRMED".equals(existing.trustLevel()) ? existing.trustLevel() : trustLevel;
      jdbcTemplate.update(
          """
          UPDATE ai_city_memory
             SET memory_fingerprint=?, reason_code=?, over_limit_type=?,
                 abnormal_pattern=?, initial_reason=?, user_correction=COALESCE(?, user_correction),
                 final_reason=?, evidence_summary=?, rectification_summary=?,
                 season_code=?, ratio_bucket=?, evidence_tags_json=?, trust_level=?,
                 source_report_id=COALESCE(?, source_report_id),
                 source_message_id=COALESCE(?, source_message_id),
                 active=TRUE, status='ACTIVE', superseded_by_id=NULL,
                 confirm_count=confirm_count+1, confirmed_at=CURRENT_TIMESTAMP(3), confirmed_by=?
           WHERE id=? AND city_code=?
          """,
          fingerprint,
          reasonCode(safeFinalReason),
          overLimitType,
          compact(initialReason, 1000),
          blankToNull(initialReason),
          blankToNull(compact(userCorrection, 2000)),
          safeFinalReason,
          compact(evidenceSummary, 12_000),
          compact(rectificationSummary, 8000),
          season,
          ratioBucket,
          tagsJson,
          effectiveTrust,
          sourceReportId,
          sourceMessageId,
          actor,
          existing.id(),
          cityCode);
      return existing.id();
    }
    jdbcTemplate.update(
        """
        INSERT INTO ai_city_memory
          (public_id, city_code, billing_point_code, over_limit_type,
           abnormal_pattern, initial_reason, user_correction, final_reason,
           evidence_summary, rectification_summary, trust_level,
           source_report_id, source_message_id, confirmed_by, memory_fingerprint,
           reason_code, season_code, ratio_bucket, evidence_tags_json, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
        """,
        UUID.randomUUID().toString(),
        cityCode,
        billingPointCode,
        overLimitType,
        compact(initialReason, 1000),
        blankToNull(initialReason),
        blankToNull(compact(userCorrection, 2000)),
        safeFinalReason,
        compact(evidenceSummary, 12_000),
        compact(rectificationSummary, 8000),
        trustLevel,
        sourceReportId,
        sourceMessageId,
        actor,
        fingerprint,
        reasonCode(safeFinalReason),
        season,
        ratioBucket,
        tagsJson);
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM ai_city_memory WHERE city_code=? AND memory_fingerprint=?",
            Long.class,
            cityCode,
            fingerprint);
    if (id == null) {
      throw new IllegalStateException("AI city memory key was not generated");
    }
    return id;
  }

  private ExistingMemory findExistingMemory(
      String cityCode, String billingPointCode, String finalReason, String fingerprint) {
    List<ExistingMemory> exact =
        jdbcTemplate.query(
            """
            SELECT id, trust_level, source_report_id, source_message_id
              FROM ai_city_memory
             WHERE city_code=? AND memory_fingerprint=?
             LIMIT 1
            """,
            (rs, row) ->
                new ExistingMemory(
                    rs.getLong("id"),
                    rs.getString("trust_level"),
                    rs.getObject("source_report_id", Long.class),
                    rs.getObject("source_message_id", Long.class)),
            cityCode,
            fingerprint);
    if (!exact.isEmpty()) {
      return exact.getFirst();
    }
    return jdbcTemplate
        .query(
            """
            SELECT id, trust_level, source_report_id, source_message_id
              FROM ai_city_memory
             WHERE city_code=? AND memory_fingerprint IS NULL
               AND billing_point_code=? AND final_reason=?
             ORDER BY id DESC LIMIT 1
            """,
            (rs, row) ->
                new ExistingMemory(
                    rs.getLong("id"),
                    rs.getString("trust_level"),
                    rs.getObject("source_report_id", Long.class),
                    rs.getObject("source_message_id", Long.class)),
            cityCode,
            billingPointCode,
            finalReason)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private void rebuildPointProfile(String cityCode, String billingPointCode) {
    Integer historicalCount =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM historical_audit_case
             WHERE city_code=? AND billing_point_code=?
            """,
            Integer.class,
            cityCode,
            billingPointCode);
    List<ReasonAggregate> reasons =
        jdbcTemplate.query(
            """
            SELECT final_reason, SUM(confirm_count) AS confirmations
              FROM ai_city_memory
             WHERE city_code=? AND billing_point_code=?
               AND active=TRUE AND status='ACTIVE'
             GROUP BY final_reason
             ORDER BY confirmations DESC, final_reason
             LIMIT 20
            """,
            (rs, row) ->
                new ReasonAggregate(rs.getString("final_reason"), rs.getInt("confirmations")),
            cityCode,
            billingPointCode);
    Integer activeMemoryCount =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM ai_city_memory
             WHERE city_code=? AND billing_point_code=?
               AND active=TRUE AND status='ACTIVE'
            """,
            Integer.class,
            cityCode,
            billingPointCode);
    Map<String, Integer> seasons = new LinkedHashMap<>();
    jdbcTemplate
        .queryForList(
            """
            SELECT data_period FROM historical_audit_case
             WHERE city_code=? AND billing_point_code=?
            """,
            String.class,
            cityCode,
            billingPointCode)
        .forEach(period -> seasons.merge(season(period), 1, Integer::sum));
    int confirmationCount = reasons.stream().mapToInt(ReasonAggregate::confirmations).sum();
    String reasonSummary =
        reasons.isEmpty()
            ? "暂无已确认原因"
            : reasons.stream()
                .limit(5)
                .map(reason -> reason.reason() + "（确认" + reason.confirmations() + "次）")
                .reduce((left, right) -> left + "、" + right)
                .orElse("暂无已确认原因");
    String summary =
        "该报账点已收录历史报告"
            + value(historicalCount)
            + "份；有效确认记忆"
            + value(activeMemoryCount)
            + "条，累计确认"
            + confirmationCount
            + "次；常见原因："
            + reasonSummary
            + "；季节分布："
            + seasons;
    jdbcTemplate.update(
        """
        INSERT INTO ai_billing_point_memory_profile
          (public_id, city_code, billing_point_code, historical_case_count,
           active_memory_count, reason_stats_json, season_stats_json, profile_summary)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          historical_case_count=VALUES(historical_case_count),
          active_memory_count=VALUES(active_memory_count),
          reason_stats_json=VALUES(reason_stats_json),
          season_stats_json=VALUES(season_stats_json),
          profile_summary=VALUES(profile_summary),
          rebuilt_at=CURRENT_TIMESTAMP(3)
        """,
        UUID.randomUUID().toString(),
        cityCode,
        billingPointCode,
        historicalCount == null ? 0 : historicalCount,
        activeMemoryCount == null ? 0 : activeMemoryCount,
        json(reasons),
        json(seasons),
        compact(summary, 12_000));
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

  private void deactivateDraftCorrections(String draftPublicId, long replacementMemoryId) {
    jdbcTemplate.update(
        """
        UPDATE ai_city_memory
           SET active = FALSE, status = 'SUPERSEDED', superseded_by_id = ?
         WHERE trust_level = 'USER_CONFIRMED'
           AND id <> ?
           AND source_message_id IN (
             SELECT m.id
               FROM report_draft_message m
               JOIN report_draft d ON d.id = m.draft_id
              WHERE d.public_id = ?
           )
        """,
        replacementMemoryId,
        replacementMemoryId,
        draftPublicId);
  }

  private void supersedeOtherDraftCorrections(
      long draftId, long messageId, long replacementMemoryId) {
    jdbcTemplate.update(
        """
        UPDATE ai_city_memory
           SET active = FALSE, status = 'SUPERSEDED', superseded_by_id = ?
         WHERE trust_level = 'USER_CONFIRMED'
           AND id <> ?
           AND source_message_id IN (
             SELECT id FROM report_draft_message WHERE draft_id = ? AND id <> ?
           )
        """,
        replacementMemoryId,
        replacementMemoryId,
        draftId,
        messageId);
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
                   a.max_ratio,
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
                    rs.getBigDecimal("max_ratio"),
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
    if (normalized.length() <= maxLength) {
      return normalized;
    }
    return maxLength <= 1 ? "…" : normalized.substring(0, maxLength - 1) + "…";
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private String fingerprint(String... values) {
    String joined = String.join("|", java.util.Arrays.stream(values).map(this::normalize).toList());
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private String normalize(String value) {
    if (value == null) {
      return "";
    }
    return Pattern.compile("[^\\p{L}\\p{N}]+")
        .matcher(value.toLowerCase(Locale.ROOT))
        .replaceAll("");
  }

  private String reasonCode(String finalReason) {
    String reason = value(finalReason);
    if (reason.contains("空调")) return "AIR_CONDITIONING";
    if (reason.contains("新增") || reason.contains("投运") || reason.contains("扩容")) {
      return "NEW_EQUIPMENT";
    }
    if (reason.contains("峰谷") || reason.contains("时段")) return "PEAK_VALLEY";
    if (reason.contains("计量") || reason.contains("电表") || reason.contains("抄表")) {
      return "METERING";
    }
    if (reason.contains("水泵") || reason.contains("供水")) return "WATER_PUMP";
    if (reason.contains("充电")) return "CHARGING_LOAD";
    if (reason.contains("照明")) return "LIGHTING";
    return "OTHER_CONFIRMED";
  }

  private List<String> deriveEvidenceTags(String content) {
    List<String> candidates =
        List.of("空调", "新增设备", "投运", "峰谷", "计量", "电表", "抄表", "水泵", "充电", "照明", "施工", "负荷");
    var tags = new ArrayList<String>();
    String safeContent = value(content);
    for (String candidate : candidates) {
      if (safeContent.contains(candidate)) {
        tags.add(candidate);
      }
    }
    return List.copyOf(tags);
  }

  private int evidenceTagScore(String storedTagsJson, List<String> currentTags) {
    if (storedTagsJson == null || storedTagsJson.isBlank() || currentTags.isEmpty()) return 0;
    int matches = 0;
    for (String tag : currentTags) {
      if (storedTagsJson.contains("\"" + tag + "\"")) {
        matches++;
      }
    }
    return Math.min(matches * 5, 15);
  }

  private String season(String period) {
    if (period == null || period.isBlank()) return null;
    try {
      int month = YearMonth.parse(period).getMonthValue();
      if (month >= 3 && month <= 5) return "SPRING";
      if (month >= 6 && month <= 8) return "SUMMER";
      if (month >= 9 && month <= 11) return "AUTUMN";
      return "WINTER";
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private String ratioBucket(BigDecimal ratio) {
    if (ratio == null) return null;
    BigDecimal absolute = ratio.abs();
    if (absolute.compareTo(BigDecimal.TEN) < 0) return "LT_10";
    if (absolute.compareTo(BigDecimal.valueOf(20)) < 0) return "10_TO_20";
    if (absolute.compareTo(BigDecimal.valueOf(30)) < 0) return "20_TO_30";
    if (absolute.compareTo(BigDecimal.valueOf(50)) < 0) return "30_TO_50";
    return "GE_50";
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("AI memory JSON could not be serialized", exception);
    }
  }

  private String value(Object value) {
    return value == null ? "" : value.toString();
  }

  public record MemoryQuery(
      String cityCode,
      String billingPointCode,
      String overLimitType,
      String period,
      BigDecimal maxRatio,
      String evidenceText) {
    public MemoryQuery(
        String cityCode,
        String billingPointCode,
        String overLimitType,
        String period,
        BigDecimal maxRatio) {
      this(cityCode, billingPointCode, overLimitType, period, maxRatio, "");
    }
  }

  public record MemoryMatch(
      String publicId,
      String cityCode,
      String billingPointCode,
      String overLimitType,
      int score,
      String summary) {}

  public record PointProfile(
      String publicId,
      String cityCode,
      String billingPointCode,
      int historicalCaseCount,
      int activeMemoryCount,
      String summary) {}

  private record ExistingMemory(
      long id, String trustLevel, Long sourceReportId, Long sourceMessageId) {}

  private record ReasonAggregate(String reason, int confirmations) {}

  private record ReportMemorySource(
      long reportId,
      String cityCode,
      String billingPointCode,
      String period,
      String overLimitType,
      BigDecimal maxRatio,
      String title,
      String situation,
      String analysis,
      String rectification,
      String initialReason,
      String finalReason,
      Long messageId,
      String userCorrection) {}

  private record CorrectionMemorySource(
      String cityCode,
      String billingPointCode,
      String period,
      String overLimitType,
      BigDecimal maxRatio) {}
}
