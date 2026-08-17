package com.threefees.report.application;

import com.threefees.ai.application.AiServiceClient;
import com.threefees.ai.application.AiServiceException;
import com.threefees.file.application.StoredFileService;
import com.threefees.identity.application.BusinessRuleException;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.application.ResourceConflictException;
import com.threefees.identity.application.ResourceNotFoundException;
import com.threefees.identity.domain.Role;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReportGenerationService {

  private static final DateTimeFormatter NUMBER_MONTH = DateTimeFormatter.ofPattern("yyyyMM");
  private static final int MAX_IMAGE_COUNT = 10;
  private static final int MAX_SINGLE_IMAGE_BYTES = 10 * 1024 * 1024;
  private static final int MAX_TOTAL_IMAGE_BYTES = 20 * 1024 * 1024;
  private static final Pattern HTML_SECTION_ANALYSIS =
      Pattern.compile("(?is)(<h2[^>]*>\\s*二、排查分析\\s*</h2>)(.*?)(<h2[^>]*>\\s*三、整改小结\\s*</h2>)");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final StoredFileService storedFileService;
  private final ReportDocumentGenerator documentGenerator;
  private final AiServiceClient aiServiceClient;
  private final boolean aiEnabled;

  public ReportGenerationService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      StoredFileService storedFileService,
      ReportDocumentGenerator documentGenerator,
      AiServiceClient aiServiceClient,
      @Value("${app.ai.enabled:false}") boolean aiEnabled) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.storedFileService = storedFileService;
    this.documentGenerator = documentGenerator;
    this.aiServiceClient = aiServiceClient;
    this.aiEnabled = aiEnabled;
  }

  @Transactional(readOnly = true)
  public List<Candidate> candidates(String cityCode, CurrentUser actor) {
    String scopedCity = scopeCity(actor, cityCode);
    var args = new ArrayList<Object>();
    StringBuilder where =
        new StringBuilder(
            " WHERE a.audit_status = 'OVER_LIMIT' AND a.report_status = 'WAITING' AND r.id IS NULL");
    if (scopedCity != null && !scopedCity.isBlank()) {
      where.append(" AND s.city_code = ?");
      args.add(scopedCity);
    }
    return jdbcTemplate.query(
        """
        SELECT s.public_id AS billing_point_period_id,
               s.billing_point_code, s.billing_point_name, s.city_code, c.name AS city_name,
               s.district_name, s.data_period, a.over_limit_type, a.max_ratio
          FROM billing_point_snapshot s
          JOIN city c ON c.code = s.city_code
          JOIN audit_result a
            ON a.billing_point_code = s.billing_point_code
           AND a.data_period = s.data_period
           AND a.city_code = s.city_code
          LEFT JOIN audit_report r ON r.billing_point_snapshot_id = s.id
        """
            + where
            + " ORDER BY a.max_ratio DESC, s.billing_point_code, s.data_period DESC LIMIT 500",
        (rs, row) ->
            new Candidate(
                rs.getString("billing_point_period_id"),
                rs.getString("billing_point_code"),
                rs.getString("billing_point_name"),
                rs.getString("city_code"),
                rs.getString("city_name"),
                rs.getString("district_name"),
                rs.getString("data_period"),
                overLimitTypeLabel(rs.getString("over_limit_type")),
                rs.getBigDecimal("max_ratio")),
        args.toArray());
  }

  @Transactional(readOnly = true)
  public InitialContent initialContent(String billingPointCode, String period, CurrentUser actor) {
    GenerationSource source = source(billingPointCode, period, actor, true);
    return new InitialContent(candidate(source), initialHtml(source));
  }

  @Transactional(readOnly = true)
  public InitialContent correctionInitialContent(String reportId, CurrentUser actor) {
    GenerationSource source = sourceForReport(reportId, actor);
    String contentHtml =
        jdbcTemplate.queryForObject(
            "SELECT situation FROM audit_report WHERE public_id = ?",
            String.class,
            reportId);
    if (contentHtml == null || contentHtml.isBlank()) {
      contentHtml = initialHtml(source);
    }
    return new InitialContent(candidate(source), contentHtml);
  }

  @Transactional(readOnly = true)
  public AnalyzeImageResult analyzeImages(AnalyzeImageCommand command, CurrentUser actor) {
    if (!aiEnabled) {
      throw new BusinessRuleException("AI_ASSISTANT_DISABLED", "AI 助手暂不可用，请先人工编辑报告。");
    }
    GenerationSource source = source(command.billingPointCode(), command.period(), actor, false);
    String contentHtml = command.contentHtml() == null ? "" : command.contentHtml();
    if (contentHtml.isBlank()) {
      throw new BusinessRuleException("REPORT_CONTENT_EMPTY", "请先生成或填写报告正文后再分析图片。");
    }
    List<AiServiceClient.AiImage> images = validateImages(command.images());
    String traceId = UUID.randomUUID().toString();
    try {
      AiServiceClient.ReportImageAnalysisResult result =
          aiServiceClient.analyzeReportImages(
              source.billingPointCode(),
              source.period(),
              contentHtml,
              command.instruction() == null ? "" : command.instruction(),
              facts(source),
              images,
              traceId);
      String updatedHtml = result.updatedContentHtml();
      if (updatedHtml == null || updatedHtml.isBlank()) {
        updatedHtml = appendAnalysis(contentHtml, result.analysisText());
      }
      return new AnalyzeImageResult(result.answer(), updatedHtml, result.analysisText());
    } catch (AiServiceException exception) {
      throw new BusinessRuleException(exception.code(), exception.getMessage());
    }
  }

  @Transactional
  public GeneratedReport generate(GenerateReportCommand command, CurrentUser actor) {
    GenerationSource source = source(command.billingPointCode(), command.period(), actor, true);
    if (existingReportForSnapshot(source.snapshotId()) != null) {
      throw formalReportExists();
    }
    return insertGeneratedReport(source, command.contentHtml(), actor);
  }

  @Transactional
  public GeneratedReport regenerate(CorrectionGenerateCommand command, CurrentUser actor) {
    GenerationSource source = sourceForReport(command.reportId(), actor);
    String contentHtml = command.contentHtml() == null ? "" : command.contentHtml();
    if (contentHtml.isBlank()) {
      throw new BusinessRuleException("REPORT_CONTENT_EMPTY", "报告正文不能为空");
    }
    byte[] wordBytes = documentGenerator.generateWordFromHtml(contentHtml);
    var word =
        storedFileService.storeGenerated(
            wordBytes,
            source.billingPointCode() + "-" + source.period() + "-电费稽核报告-更正.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "FORMAL_REPORT_WORD",
            actor.username());
    String title = titleFromHtml(contentHtml, source);
    String snapshot = reportSnapshot(source, contentHtml);
    int updated =
        jdbcTemplate.update(
            """
            UPDATE audit_report
               SET status = 'CORRECTED',
                   title = ?,
                   situation = ?,
                   analysis = '',
                   rectification = '',
                   word_file_id = ?,
                   business_snapshot_json = ?,
                   correction_reason = ?,
                   corrected_at = CURRENT_TIMESTAMP(3),
                   corrected_by = ?,
                   updated_at = CURRENT_TIMESTAMP(3),
                   updated_by = ?,
                   version = version + 1
             WHERE public_id = ?
            """,
            title,
            contentHtml,
            word.id(),
            snapshot,
            command.reason(),
            actor.username(),
            actor.username(),
            command.reportId());
    if (updated == 0) {
      storedFileService.deleteGenerated(word);
      throw new ResourceNotFoundException("正式报告");
    }
    return new GeneratedReport(command.reportId(), source.reportNumber());
  }

  private GeneratedReport insertGeneratedReport(
      GenerationSource source, String rawContentHtml, CurrentUser actor) {
    String contentHtml = rawContentHtml == null ? "" : rawContentHtml;
    if (contentHtml.isBlank()) {
      throw new BusinessRuleException("REPORT_CONTENT_EMPTY", "报告正文不能为空");
    }
    String title = titleFromHtml(contentHtml, source);
    byte[] wordBytes = documentGenerator.generateWordFromHtml(contentHtml);
    var word =
        storedFileService.storeGenerated(
            wordBytes,
            source.billingPointCode() + "-" + source.period() + "-电费稽核报告.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "FORMAL_REPORT_WORD",
            actor.username());
    String publicId = UUID.randomUUID().toString();
    String reportNumber = nextReportNumber(source.period());
    try {
      jdbcTemplate.update(
          """
          INSERT INTO audit_report
            (public_id, report_number, billing_point_snapshot_id, source_type, status,
             title, situation, analysis, rectification, word_file_id, pdf_file_id,
             business_snapshot_json, updated_by)
          VALUES (?, ?, ?, 'GENERATED', 'GENERATED', ?, ?, '', '', ?, NULL, ?, ?)
          """,
          publicId,
          reportNumber,
          source.snapshotId(),
          title,
          contentHtml,
          word.id(),
          reportSnapshot(source, contentHtml),
          actor.username());
      jdbcTemplate.update(
          """
          UPDATE audit_result
             SET report_status = 'GENERATED',
                 updated_at = CURRENT_TIMESTAMP(3)
           WHERE billing_point_code = ? AND data_period = ? AND city_code = ?
          """,
          source.billingPointCode(),
          source.period(),
          source.cityCode());
    } catch (DuplicateKeyException exception) {
      storedFileService.deleteGenerated(word);
      if (existingReportForSnapshot(source.snapshotId()) != null) {
        throw formalReportExists();
      }
      throw exception;
    }
    return new GeneratedReport(publicId, reportNumber);
  }

  private String initialHtml(GenerationSource source) {
    String title =
        source.billingPointName()
            + "电费稽核说明-"
            + formatDate(source.periodStart())
            + "至"
            + formatDate(source.periodEnd());
    String situation =
        "报账点“"
            + source.billingPointName()
            + "”"
            + formatMonth(source.period())
            + "实际总耗电量为"
            + decimal(source.actualEnergy())
            + "kWh，实际报账金额为"
            + decimal(source.actualAmount())
            + "元。经系统稽核，该报账点本期用电存在超标情况，超标类型为"
            + overLimitTypeLabel(source.overLimitType())
            + "，最大超标比例为"
            + percent(source.maxRatio())
            + "。";
    return "<h1>"
        + escape(title)
        + "</h1><h2>一、情况说明</h2><p>"
        + escape(situation)
        + "</p><h2>二、排查分析</h2><p><br /></p><h2>三、整改小结</h2><p><br /></p>";
  }

  private GenerationSource source(
      String billingPointCode, String period, CurrentUser actor, boolean requireEligible) {
    List<GenerationSource> rows =
        jdbcTemplate.query(
            sourceSql("WHERE s.billing_point_code = ? AND s.data_period = ?"),
            (rs, row) -> mapSource(rs),
            billingPointCode,
            period);
    if (rows.isEmpty()) {
      throw new ResourceNotFoundException("报账点账期不存在");
    }
    if (rows.size() > 1) {
      throw new ResourceConflictException(
          "REPORT_GENERATION_PERIOD_AMBIGUOUS",
          "报账点编码和账期匹配到多条快照，请检查城市维度数据。");
    }
    GenerationSource source = rows.getFirst();
    requireScope(actor, source.cityCode());
    if (requireEligible) {
      if (!"OVER_LIMIT".equals(source.auditStatus())) {
        throw new BusinessRuleException("REPORT_GENERATION_NOT_OVER_LIMIT", "当前报账点账期不是超标状态");
      }
      if (source.reportId() != null) {
        throw formalReportExists();
      }
    }
    return source;
  }

  private GenerationSource sourceForReport(String reportId, CurrentUser actor) {
    List<GenerationSource> rows =
        jdbcTemplate.query(
            sourceSql("WHERE r.public_id = ?"),
            (rs, row) -> mapSource(rs),
            reportId);
    GenerationSource source =
        rows.stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("正式报告"));
    requireScope(actor, source.cityCode());
    return source;
  }

  private String sourceSql(String where) {
    return """
        SELECT s.id, s.public_id, s.billing_point_code, s.billing_point_name,
               s.city_code, c.name AS city_name, s.district_name, s.data_period,
               s.period_start, s.period_end, s.data_json,
               a.audit_status, a.over_limit_type, a.max_ratio,
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
               a.detail_json, r.public_id AS report_id, r.report_number
          FROM billing_point_snapshot s
          JOIN city c ON c.code = s.city_code
          JOIN audit_result a
            ON a.billing_point_code = s.billing_point_code
           AND a.data_period = s.data_period
           AND a.city_code = s.city_code
          LEFT JOIN audit_report r ON r.billing_point_snapshot_id = s.id
        """
        + where;
  }

  private GenerationSource mapSource(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new GenerationSource(
        rs.getLong("id"),
        rs.getString("public_id"),
        rs.getString("billing_point_code"),
        rs.getString("billing_point_name"),
        rs.getString("city_code"),
        rs.getString("city_name"),
        rs.getString("district_name"),
        rs.getString("data_period"),
        rs.getObject("period_start", LocalDate.class),
        rs.getObject("period_end", LocalDate.class),
        parseJson(rs.getString("data_json")),
        rs.getString("audit_status"),
        rs.getString("over_limit_type"),
        rs.getBigDecimal("max_ratio"),
        rs.getBigDecimal("actual_energy"),
        rs.getBigDecimal("actual_amount"),
        parseJson(rs.getString("detail_json")),
        rs.getString("report_id"),
        rs.getString("report_number"));
  }

  private List<AiServiceClient.AiImage> validateImages(List<AnalyzeImageCommand.ImageInput> inputs) {
    if (inputs == null || inputs.isEmpty()) {
      throw new BusinessRuleException("AI_IMAGES_REQUIRED", "请至少选择一张需要分析的图片。");
    }
    if (inputs.size() > MAX_IMAGE_COUNT) {
      throw new BusinessRuleException("AI_IMAGES_TOO_MANY", "一次最多分析 10 张图片。");
    }
    int totalBytes = 0;
    List<AiServiceClient.AiImage> images = new ArrayList<>();
    for (AnalyzeImageCommand.ImageInput input : inputs) {
      if (!"image/png".equals(input.mediaType()) && !"image/jpeg".equals(input.mediaType())) {
        throw new BusinessRuleException("AI_IMAGE_TYPE_UNSUPPORTED", "分析图片只支持 PNG 或 JPEG。");
      }
      byte[] bytes;
      try {
        bytes = Base64.getDecoder().decode(input.base64Data());
      } catch (IllegalArgumentException exception) {
        throw new BusinessRuleException("AI_IMAGE_INVALID", "图片内容不是有效的 Base64 数据。");
      }
      if (bytes.length == 0 || bytes.length > MAX_SINGLE_IMAGE_BYTES) {
        throw new BusinessRuleException("AI_IMAGE_TOO_LARGE", "单张分析图片不能超过 10 MiB。");
      }
      totalBytes += bytes.length;
      if (totalBytes > MAX_TOTAL_IMAGE_BYTES) {
        throw new BusinessRuleException("AI_IMAGES_TOO_LARGE", "分析图片总大小不能超过 20 MiB。");
      }
      images.add(
          new AiServiceClient.AiImage(
              input.fileName() == null || input.fileName().isBlank() ? "现场图片" : input.fileName(),
              input.mediaType(),
              bytes));
    }
    return images;
  }

  private List<AiServiceClient.Fact> facts(GenerationSource source) {
    return List.of(
        new AiServiceClient.Fact("报账点编码", source.billingPointCode()),
        new AiServiceClient.Fact("报账点名称", source.billingPointName()),
        new AiServiceClient.Fact("所属城市", source.cityName()),
        new AiServiceClient.Fact("所属区县", source.district() == null ? "" : source.district()),
        new AiServiceClient.Fact("账期", source.period()),
        new AiServiceClient.Fact("稽核状态", "超标"),
        new AiServiceClient.Fact("超标类型", overLimitTypeLabel(source.overLimitType())),
        new AiServiceClient.Fact("最大超标比例", percent(source.maxRatio())),
        new AiServiceClient.Fact("实际总耗电量", decimal(source.actualEnergy()) + "kWh"),
        new AiServiceClient.Fact("实际报账金额", decimal(source.actualAmount()) + "元"));
  }

  private String appendAnalysis(String contentHtml, String analysisText) {
    if (analysisText == null || analysisText.isBlank()) {
      return contentHtml;
    }
    String paragraph = "<p>" + escape(analysisText).replace("\n", "<br />") + "</p>";
    var matcher = HTML_SECTION_ANALYSIS.matcher(contentHtml);
    if (matcher.find()) {
      return matcher.replaceFirst("$1$2" + java.util.regex.Matcher.quoteReplacement(paragraph) + "$3");
    }
    return contentHtml + paragraph;
  }

  private ResourceConflictException formalReportExists() {
    return new ResourceConflictException(
        "FORMAL_REPORT_EXISTS", "该报账点当前账期已生成正式报告，请刷新页面后查看。");
  }

  private Candidate candidate(GenerationSource source) {
    return new Candidate(
        source.snapshotPublicId(),
        source.billingPointCode(),
        source.billingPointName(),
        source.cityCode(),
        source.cityName(),
        source.district(),
        source.period(),
        overLimitTypeLabel(source.overLimitType()),
        source.maxRatio());
  }

  private String existingReportForSnapshot(long snapshotId) {
    List<String> ids =
        jdbcTemplate.queryForList(
            "SELECT public_id FROM audit_report WHERE billing_point_snapshot_id = ?",
            String.class,
            snapshotId);
    return ids.isEmpty() ? null : ids.getFirst();
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
      // Concurrent creation is serialized by the row lock below.
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

  private String titleFromHtml(String html, GenerationSource source) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("(?is)<h1[^>]*>(.*?)</h1>").matcher(html == null ? "" : html);
    if (matcher.find()) {
      String text = matcher.group(1).replaceAll("<[^>]+>", "").trim();
      if (!text.isBlank()) {
        return text;
      }
    }
    return source.billingPointName() + "电费稽核报告";
  }

  private String reportSnapshot(GenerationSource source, String contentHtml) {
    return writeJson(
        Map.of(
            "snapshotId", source.snapshotPublicId(),
            "billingPoint", source.snapshotJson(),
            "audit", source.auditJson(),
            "contentHtml", contentHtml));
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
      throw new AccessDeniedException("Report generation is outside city scope");
    }
  }

  private JsonNode parseJson(String value) {
    if (value == null || value.isBlank()) {
      return objectMapper.createObjectNode();
    }
    try {
      return objectMapper.readTree(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted report source contains invalid JSON", exception);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Report snapshot could not be serialized", exception);
    }
  }

  private String formatDate(LocalDate date) {
    return date.getYear() + "年" + date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
  }

  private String formatMonth(String period) {
    YearMonth month = YearMonth.parse(period);
    return month.getYear() + "年" + month.getMonthValue() + "月";
  }

  private String decimal(BigDecimal value) {
    return value == null ? "0" : value.stripTrailingZeros().toPlainString();
  }

  private String percent(BigDecimal value) {
    return value == null ? "0%" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
  }

  private static String overLimitTypeLabel(String value) {
    return switch (value == null ? "" : value) {
      case "ONLY_YOY" -> "仅同比超标";
      case "ONLY_MOM" -> "仅环比超标";
      case "ONLY_RATED" -> "仅额定标杆超标";
      case "MULTIPLE" -> "多项超标";
      case "NONE" -> "未超标";
      default -> value == null ? "" : value;
    };
  }

  private String escape(String value) {
    return value == null
        ? ""
        : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  public record Candidate(
      String billingPointPeriodId,
      String billingPointCode,
      String billingPointName,
      String cityCode,
      String cityName,
      String district,
      String period,
      String overLimitType,
      BigDecimal maxExceedRatio) {}

  public record InitialContent(Candidate candidate, String contentHtml) {}

  public record GenerateReportCommand(String billingPointCode, String period, String contentHtml) {}

  public record CorrectionGenerateCommand(
      String reportId, String billingPointCode, String period, String contentHtml, String reason) {}

  public record GeneratedReport(String reportId, String reportNumber) {}

  public record AnalyzeImageCommand(
      String billingPointCode,
      String period,
      String contentHtml,
      String instruction,
      List<ImageInput> images) {
    public record ImageInput(String fileName, String mediaType, String base64Data) {}
  }

  public record AnalyzeImageResult(String answer, String updatedContentHtml, String analysisText) {}

  private record GenerationSource(
      long snapshotId,
      String snapshotPublicId,
      String billingPointCode,
      String billingPointName,
      String cityCode,
      String cityName,
      String district,
      String period,
      LocalDate periodStart,
      LocalDate periodEnd,
      JsonNode snapshotJson,
      String auditStatus,
      String overLimitType,
      BigDecimal maxRatio,
      BigDecimal actualEnergy,
      BigDecimal actualAmount,
      JsonNode auditJson,
      String reportId,
      String reportNumber) {}
}
