package com.threefees.report.application;

import com.threefees.ai.application.AiServiceClient;
import com.threefees.ai.application.AiServiceClient.AgentContext;
import com.threefees.ai.application.AiServiceClient.AiImage;
import com.threefees.ai.application.AiServiceClient.ConversationTurn;
import com.threefees.ai.application.AiServiceClient.Fact;
import com.threefees.ai.application.AiServiceClient.ImageAnalysis;
import com.threefees.ai.application.AiServiceClient.Reference;
import com.threefees.ai.application.AiServiceClient.ReportSections;
import com.threefees.ai.application.AiServiceException;
import com.threefees.ai.application.CityMemoryService;
import com.threefees.ai.application.CityMemoryService.MemoryQuery;
import com.threefees.file.application.StoredFileService;
import com.threefees.file.domain.StoredFile;
import com.threefees.identity.application.BusinessRuleException;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.application.ResourceConflictException;
import com.threefees.identity.application.ResourceNotFoundException;
import com.threefees.identity.domain.Role;
import com.threefees.task.application.BusinessTaskRepository;
import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskStatus;
import com.threefees.task.domain.TaskType;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReportDraftService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReportDraftService.class);
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
  private static final Pattern INLINE_FIGURE =
      Pattern.compile("(?is)<figure\\b[^>]*data-file-id=[\"']([^\"']+)[\"'][^>]*>.*?</figure>");
  private static final Pattern INLINE_IMAGE_ROW =
      Pattern.compile(
          "(?is)<div\\b[^>]*class=[\"'][^\"']*inline-image-row[^\"']*[\"'][^>]*>.*?</div>");
  private static final Pattern INLINE_FILE_REFERENCE =
      Pattern.compile("(?is)data-file-id=[\"']([^\"']+)[\"']");
  private static final Pattern INLINE_IMAGE_TAG = Pattern.compile("(?is)<img\\b[^>]*>");
  private static final Pattern INLINE_BASE64_FIGURE =
      Pattern.compile(
          "(?is)<figure\\b[^>]*>\\s*"
              + "<img\\b[^>]*src=[\"']data:image/(png|jpe?g);base64,([^\"']+)[\"'][^>]*?/?>"
              + "\\s*</figure>");
  private static final Pattern INLINE_BASE64_IMAGE =
      Pattern.compile(
          "(?is)<img\\b[^>]*src=[\"']data:image/(png|jpe?g);base64,([^\"']+)[\"'][^>]*?/?>");
  private static final Pattern BASE64_IMAGE_MARKER =
      Pattern.compile("(?is)data:image/(?:png|jpe?g);base64,");
  private static final Pattern HTML_ELEMENT = Pattern.compile("(?is)<[a-z][^>]*>");
  private static final String EQUIPMENT_IMAGE_LABELS =
      "(?:设备情况|机房全景图|设备机柜现场图)";
  private static final Pattern IMAGE_FOLLOWED_BY_LABEL_BLOCK =
      Pattern.compile(
          "(?is)(<figure\\b[^>]*data-file-id=[\"'][^\"']+[\"'][^>]*>.*?</figure>)\\s*"
              + "(<(p|div|h[1-6])\\b[^>]*>\\s*"
              + EQUIPMENT_IMAGE_LABELS
              + "\\s*[：:].*?</\\3>)");
  private static final Pattern IMAGE_FOLLOWED_BY_LABEL_TEXT =
      Pattern.compile(
          "(?is)(<figure\\b[^>]*data-file-id=[\"'][^\"']+[\"'][^>]*>.*?</figure>)\\s*("
              + EQUIPMENT_IMAGE_LABELS
              + "\\s*[：:][^<\\r\\n]*)");
  private static final String ANALYSIS_REASON_MARKERS =
      "(?:本期[^<\\r\\n]{0,40}超标原因|超标原因|主要原因|原因分析)";
  private static final Pattern ANALYSIS_REASON_BLOCK =
      Pattern.compile(
          "(?is)\\s*<(p|div)\\b[^>]*>\\s*(?:(?!</\\1>).)*?"
              + ANALYSIS_REASON_MARKERS
              + "(?:(?!</\\1>).)*?</\\1>\\s*");
  private static final Pattern ANALYSIS_REASON_TEXT_LINE =
      Pattern.compile("(?im)^\\s*.*" + ANALYSIS_REASON_MARKERS + ".*(?:\\R|$)");
  private static final Pattern ADDITIVE_RATED_POWER_FORMULA =
      Pattern.compile(
          "(?is)（?\\(?\\s*\\d+(?:\\.\\d+)?\\s*(?:kW|KW)?\\s*[+＋]\\s*\\d+(?:\\.\\d+)?\\s*(?:kW|KW)?\\s*\\)?）?\\s*(?:kW|KW)?\\s*[*×xX]\\s*24\\s*(?:小时|H|h)(?:\\s*[*×xX]\\s*\\d+\\s*天?)?");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final AiServiceClient aiServiceClient;
  private final CityMemoryService cityMemoryService;
  private final StoredFileService storedFileService;
  private final BusinessTaskRepository taskRepository;
  private final int maxHistoryCases;

  public ReportDraftService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      AiServiceClient aiServiceClient,
      CityMemoryService cityMemoryService,
      StoredFileService storedFileService,
      BusinessTaskRepository taskRepository,
      @org.springframework.beans.factory.annotation.Value("${app.ai.max-history-cases:8}")
          int maxHistoryCases) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.aiServiceClient = aiServiceClient;
    this.cityMemoryService = cityMemoryService;
    this.storedFileService = storedFileService;
    this.taskRepository = taskRepository;
    this.maxHistoryCases = Math.max(1, Math.min(maxHistoryCases, 20));
  }

  @Transactional
  public Draft createOrResume(String billingPointPeriodId, CurrentUser actor) {
    Snapshot snapshot = snapshot(billingPointPeriodId);
    requireScope(actor, snapshot.cityCode());
    if (!"OVER_LIMIT".equals(snapshot.auditStatus())) {
      throw new BusinessRuleException("REPORT_DRAFT_NOT_ELIGIBLE", "仅超标账期可以生成报告");
    }
    Draft existing = findBySnapshotId(snapshot.id());
    if (existing != null) {
      return existing;
    }
    if (snapshot.reportId() != null) {
      throw new ResourceConflictException("FORMAL_REPORT_EXISTS", "该报账点账期已有正式报告");
    }
    String publicId = UUID.randomUUID().toString();
    ReportSections sections =
        new ReportSections(
            "《" + snapshot.billingPointName() + "电费稽核说明》",
            "报账点编码为" + snapshot.billingPointCode() + "，账期为" + snapshot.period() + "，系统稽核结果为超标。",
            "",
            "");
    var keyHolder = new GeneratedKeyHolder();
    try {
      jdbcTemplate.update(
          connection ->
              insertDraft(connection, publicId, snapshot.id(), sections, actor.username()),
          keyHolder);
    } catch (DuplicateKeyException exception) {
      Draft concurrent = findBySnapshotId(snapshot.id());
      if (concurrent != null) {
        return concurrent;
      }
      throw exception;
    }
    Number key = keyHolder.getKey();
    if (key == null) {
      throw new IllegalStateException("Draft key was not generated");
    }
    saveVersion(key.longValue(), 0, "INITIAL", sections, List.of(), actor.username());
    return find(publicId, actor);
  }

  @Transactional
  public Draft createCorrection(String reportId, String reason, CurrentUser actor) {
    String safeReason = reason == null ? "" : reason.trim();
    if (safeReason.isBlank()) {
      throw new BusinessRuleException("CORRECTION_REASON_REQUIRED", "请填写更正原因");
    }
    SourceReport source = sourceReport(reportId);
    requireScope(actor, source.cityCode());
    CorrectionContent correctionContent = prepareCorrectionContent(source, actor.username());
    ReportSections sections = correctionContent.sections();
    List<String> imageIds = correctionContent.imageIds();
    Draft existing = findBySnapshotId(source.snapshotId());
    if (existing == null) {
      String publicId = UUID.randomUUID().toString();
      var keyHolder = new GeneratedKeyHolder();
      jdbcTemplate.update(
          connection ->
              insertCorrectionDraft(
                  connection,
                  publicId,
                  source.snapshotId(),
                  reportId,
                  sections,
                  imageIds,
                  safeReason,
                  actor.username()),
          keyHolder);
      Number key = keyHolder.getKey();
      if (key == null) {
        throw new IllegalStateException("Correction draft key was not generated");
      }
      bindImages(key.longValue(), imageIds, List.of(), actor.username());
      saveVersion(key.longValue(), 0, "CORRECTION", sections, imageIds, actor.username());
      return find(publicId, actor);
    }
    if ("GENERATING".equals(existing.status())) {
      throw new ResourceConflictException("DRAFT_GENERATING", "工作稿正在生成报告，请稍后再试");
    }
    int nextVersion = existing.currentVersion() + 1;
    jdbcTemplate.update(
        """
        UPDATE report_draft
           SET status = 'CORRECTING',
               title = ?, situation = ?, analysis = ?, rectification = ?,
               current_image_file_ids_json = ?,
               current_version_no = ?,
               formal_report_public_id = ?,
               ai_final_reason = ?,
               analysis_status = 'PENDING_ANALYSIS',
               analysis_task_public_id = NULL,
               analysis_error_code = NULL,
               analysis_submitted_at = NULL,
               analysis_completed_at = NULL,
               updated_at = CURRENT_TIMESTAMP(3), updated_by = ?, version = version + 1
         WHERE id = ?
        """,
        sections.title(),
        sections.situation(),
        sections.analysis(),
        sections.rectification(),
        writeJson(imageIds),
        nextVersion,
        reportId,
        safeReason,
        actor.username(),
        existing.id());
    bindImages(existing.id(), imageIds, List.of(), actor.username());
    saveVersion(existing.id(), nextVersion, "CORRECTION", sections, imageIds, actor.username());
    return find(existing.publicId(), actor);
  }

  @Transactional
  public boolean discardUnusedCorrection(String publicId, CurrentUser actor) {
    Draft draft = find(publicId, actor);
    if (!"CORRECTING".equals(draft.status())
        || draft.formalReportId() == null
        || draft.formalReportId().isBlank()
        || draft.analysisSubmittedAt() != null) {
      return false;
    }
    DraftVersion correctionBaseline = latestCorrectionVersion(draft.id());
    if (correctionBaseline == null
        || !sameSections(draft.sections(), correctionBaseline.sections())
        || !draft.currentImageFileIds().equals(correctionBaseline.imageFileIds())
        || hasMessagesSince(draft.id(), correctionBaseline.createdAt())) {
      return false;
    }
    DraftVersion previous = previousVersion(draft.id(), correctionBaseline.version());
    if (previous == null) {
      jdbcTemplate.update("DELETE FROM report_draft WHERE id = ?", draft.id());
      return true;
    }
    jdbcTemplate.update(
        """
        UPDATE report_draft
           SET status = 'FORMALIZED',
               title = ?, situation = ?, analysis = ?, rectification = ?,
               current_version_no = ?,
               current_image_file_ids_json = ?,
               analysis_status = 'FORMALIZED',
               analysis_task_public_id = NULL,
               analysis_error_code = NULL,
               analysis_submitted_at = NULL,
               analysis_completed_at = NULL,
               updated_at = CURRENT_TIMESTAMP(3), updated_by = ?, version = version + 1
         WHERE id = ? AND status = 'CORRECTING'
        """,
        previous.sections().title(),
        previous.sections().situation(),
        previous.sections().analysis(),
        previous.sections().rectification(),
        previous.version(),
        writeJson(previous.imageFileIds()),
        actor.username(),
        draft.id());
    bindImages(draft.id(), previous.imageFileIds(), List.of(), actor.username());
    return true;
  }

  @Transactional
  public Draft find(String publicId, CurrentUser actor) {
    Draft draft = loadDraft(publicId);
    requireScope(actor, draft.cityCode());
    if (syncImageAnalysisTaskStatus(draft, actor.username())) {
      draft = loadDraft(publicId);
    }
    return draft;
  }

  @Transactional(readOnly = true)
  public DraftImageAccess imageContent(String publicId, String imageFileId, CurrentUser actor) {
    Draft draft = find(publicId, actor);
    if (!draft.currentImageFileIds().contains(imageFileId)
        && !isBoundDraftImage(draft.id(), imageFileId)) {
      throw new ResourceNotFoundException("工作稿图片");
    }
    StoredFile file = storedFileService.find(imageFileId);
    return new DraftImageAccess(file, storedFileService.resource(file));
  }

  @Transactional
  public Draft assist(
      String publicId,
      String intent,
      String instruction,
      List<String> imageFileIds,
      long expectedVersion,
      String traceId,
      CurrentUser actor) {
    Draft draft = find(publicId, actor);
    ensureEditable(draft);
    requireExpectedVersion(draft, expectedVersion);
    String safeInstruction = requireInstruction(instruction);
    String normalizedIntent = classifyIntent(intent, safeInstruction);
    if ("IMAGE_ANALYSIS".equals(normalizedIntent)) {
      return submitImageAnalysis(publicId, safeInstruction, imageFileIds, expectedVersion, actor);
    }
    List<String> requestedImageIds = imageFileIds == null ? List.of() : List.copyOf(imageFileIds);
    if (!"IMAGE_ANALYSIS".equals(normalizedIntent) && !requestedImageIds.isEmpty()) {
      throw new BusinessRuleException("AI_IMAGES_NOT_ALLOWED", "仅图片分析可以携带图片");
    }
    if (requestedImageIds.stream().distinct().count() != requestedImageIds.size()) {
      throw new BusinessRuleException("AI_IMAGES_INVALID", "图片列表存在重复图片");
    }
    List<String> reportImageIds =
        "IMAGE_ANALYSIS".equals(normalizedIntent)
            ? Stream.concat(draft.currentImageFileIds().stream(), requestedImageIds.stream())
                .distinct()
                .toList()
            : draft.currentImageFileIds();
    if ("IMAGE_ANALYSIS".equals(normalizedIntent) && reportImageIds.isEmpty()) {
      throw new BusinessRuleException("AI_IMAGES_REQUIRED", "请先在报告正文中粘贴至少一张图片");
    }
    List<AnalysisImageInput> modelInputs =
        "IMAGE_ANALYSIS".equals(normalizedIntent)
            ? analysisImageInputs(draft, reportImageIds, actor.username(), true)
            : List.of();
    List<AiImage> images = modelInputs.stream().map(AnalysisImageInput::image).toList();
    List<Fact> facts = factsForIntent(draft, normalizedIntent);
    AgentContext context = agentContext(draft, normalizedIntent);
    ReportSections updated;
    String answer;
    var result =
        aiServiceClient.assist(
            UUID.randomUUID().toString(),
            normalizedIntent,
            safeInstruction,
            draft.sections(),
            facts,
            images,
            context,
            traceId);
    updated = result.updatedSections();
    answer = result.answer();
    boolean changed = !"ASK".equals(normalizedIntent);
    if (!changed && updated != null) {
      throw new AiServiceException("AI_INTENT_BOUNDARY_VIOLATION", "普通问答不得修改报告正文", false);
    }
    if (changed && updated == null) {
      throw new AiServiceException("AI_RESPONSE_INCOMPLETE", "AI 未返回完整报告正文", false);
    }
    if (changed) {
      updated = preserveInlineFigures(draft.sections(), updated);
      updated = sanitizeAiInlineImages(updated, reportImageIds);
      updated = normalizeAiReportText(updated);
    }
    if ("CORRECTION".equals(normalizedIntent)
        && (result.finalReason() == null || result.finalReason().isBlank())) {
      throw new AiServiceException(
          "AI_CORRECTION_REASON_MISSING", "AI 未能提取用户确认的最终原因，请换一种说法后重试", false);
    }
    int version = draft.currentVersion();
    if (changed) {
      version++;
      updateDraft(
          draft.id(),
          updated,
          reportImageIds,
          version,
          result.initialReason(),
          result.finalReason(),
          actor.username(),
          expectedVersion);
      bindImages(
          draft.id(),
          reportImageIds,
          expandGroupedImageAnalyses(reportImageIds, modelInputs, result.imageAnalyses()),
          actor.username());
      saveVersion(draft.id(), version, normalizedIntent, updated, reportImageIds, actor.username());
    } else {
      lockVersion(draft.id(), expectedVersion);
    }
    long messageId =
        saveMessage(
            draft,
            normalizedIntent,
            safeInstruction,
            answer,
            changed,
            reportImageIds,
            result.initialReason(),
            result.finalReason(),
            modelInputs.size(),
            context,
            actor.username());
    if ("CORRECTION".equals(normalizedIntent)) {
      cityMemoryService.rememberUserCorrection(
          draft.id(),
          messageId,
          safeInstruction,
          result.initialReason(),
          result.finalReason(),
          updated.analysis(),
          updated.rectification(),
          actor.username());
    }
    return find(publicId, actor);
  }

  @Transactional
  public Draft submitImageAnalysis(
      String publicId,
      String instruction,
      List<String> imageFileIds,
      long expectedVersion,
      CurrentUser actor) {
    Draft draft = find(publicId, actor);
    ensureEditable(draft);
    requireExpectedVersion(draft, expectedVersion);
    if ("AI_ANALYZING".equals(draft.analysisStatus())) {
      throw new ResourceConflictException("AI_ANALYSIS_RUNNING", "AI正在后台分析，请勿重复提交");
    }
    String safeInstruction = requireInstruction(instruction);
    List<String> requestedImageIds = imageFileIds == null ? List.of() : List.copyOf(imageFileIds);
    if (requestedImageIds.stream().distinct().count() != requestedImageIds.size()) {
      throw new BusinessRuleException("AI_IMAGES_INVALID", "图片列表存在重复图片");
    }
    List<String> reportImageIds =
        Stream.concat(draft.currentImageFileIds().stream(), requestedImageIds.stream())
            .distinct()
            .toList();
    validateAnalysisImages(reportImageIds, actor.username());
    String businessKey = imageAnalysisBusinessKey(draft);
    String payloadJson =
        writeJson(
            new ImageAnalysisTaskPayload(
                publicId, draft.currentVersion(), safeInstruction, reportImageIds, draft.sections()));
    var existing = taskRepository.findByTypeAndBusinessKey(TaskType.AI_IMAGE_ANALYSIS, businessKey);
    if (existing.isPresent()) {
      BusinessTask task = existing.get();
      if (task.status() == TaskStatus.QUEUED
          || task.status() == TaskStatus.RUNNING
          || task.status() == TaskStatus.RETRY_WAIT) {
        throw new ResourceConflictException("AI_ANALYSIS_RUNNING", "AI正在后台分析，请勿重复提交");
      }
      if (!taskRepository.requeueWithPayload(task.publicId(), payloadJson, actor.username())) {
        throw new ResourceConflictException("AI_TASK_REQUEUE_FAILED", "AI分析任务状态已变化，请刷新后重试");
      }
      markImageAnalysisQueued(draft, reportImageIds, task.publicId(), expectedVersion, actor.username());
      bindImages(draft.id(), reportImageIds, List.of(), actor.username());
      return find(publicId, actor);
    }
    try {
      BusinessTask task =
          taskRepository.create(
              TaskType.AI_IMAGE_ANALYSIS,
              businessKey,
              payloadJson,
              actor.username(),
              1);
      markImageAnalysisQueued(draft, reportImageIds, task.publicId(), expectedVersion, actor.username());
      bindImages(draft.id(), reportImageIds, List.of(), actor.username());
      return find(publicId, actor);
    } catch (DuplicateKeyException exception) {
      return find(publicId, actor);
    }
  }

  @Transactional
  public void requeueImageAnalysisTask(BusinessTask task, CurrentUser actor) {
    ImageAnalysisTaskPayload payload = readImageAnalysisPayload(task.payloadJson());
    Draft draft = find(payload.draftId(), actor);
    ensureEditable(draft);
    validateAnalysisImages(payload.imageFileIds(), actor.username());
    if (!taskRepository.requeueWithPayload(task.publicId(), task.payloadJson(), actor.username())) {
      throw new ResourceConflictException("TASK_NOT_RETRYABLE", "只有已失败或已完成的AI任务可以重新提交");
    }
    markImageAnalysisQueued(draft, payload.imageFileIds(), task.publicId(), draft.entityVersion(), actor.username());
  }

  private String imageAnalysisBusinessKey(Draft draft) {
    return "AI_IMAGE_ANALYSIS:DRAFT:"
        + draft.publicId()
        + ":CONTENT_VERSION:"
        + draft.currentVersion();
  }

  private boolean syncImageAnalysisTaskStatus(Draft draft, String actor) {
    if (!"AI_ANALYZING".equals(draft.analysisStatus())) {
      return false;
    }
    java.util.Optional<BusinessTask> task =
        draft.analysisTaskId() == null || draft.analysisTaskId().isBlank()
            ? taskRepository.findByTypeAndBusinessKey(
                TaskType.AI_IMAGE_ANALYSIS, imageAnalysisBusinessKey(draft))
            : taskRepository.findByPublicId(draft.analysisTaskId());
    if (task.isEmpty()) {
      return false;
    }
    BusinessTask current = task.get();
    if (current.status() == TaskStatus.FAILED) {
      markImageAnalysisFailed(
          draft.id(),
          current.errorCode() == null || current.errorCode().isBlank()
              ? "AI_IMAGE_ANALYSIS_FAILED"
              : current.errorCode(),
          actor);
      return true;
    }
    if (current.status() == TaskStatus.SUCCEEDED) {
      markImageAnalysisSucceeded(draft.id(), actor);
      return true;
    }
    return false;
  }

  private void markImageAnalysisQueued(
      Draft draft,
      List<String> imageFileIds,
      String taskPublicId,
      long expectedEntityVersion,
      String actor) {
    int updated =
        jdbcTemplate.update(
            """
            UPDATE report_draft
               SET current_image_file_ids_json = ?,
                   analysis_status = 'AI_ANALYZING',
                   analysis_task_public_id = ?,
                   analysis_error_code = NULL,
                   analysis_submitted_at = CURRENT_TIMESTAMP(3),
                   analysis_completed_at = NULL,
                   updated_at = CURRENT_TIMESTAMP(3),
                   updated_by = ?,
                   version = version + 1
             WHERE id = ? AND version = ? AND status IN ('DRAFT', 'CORRECTING', 'AI_COMPLETED', 'AI_FAILED')
            """,
            writeJson(imageFileIds),
            taskPublicId,
            actor,
            draft.id(),
            expectedEntityVersion);
    if (updated != 1) {
      throw new ResourceConflictException("STALE_DRAFT_VERSION", "工作稿已变化，请刷新后重试");
    }
  }

  private ImageAnalysisTaskPayload readImageAnalysisPayload(String payloadJson) {
    try {
      return objectMapper.readValue(payloadJson, ImageAnalysisTaskPayload.class);
    } catch (JacksonException exception) {
      throw new BusinessRuleException("TASK_PAYLOAD_INVALID", "AI图片分析任务数据异常，请重新提交");
    }
  }

  private ImageAnalysisTaskPayload readImageAnalysisPayloadByTaskId(String taskPublicId) {
    if (taskPublicId == null || taskPublicId.isBlank()) {
      return null;
    }
    return taskRepository
        .findByPublicId(taskPublicId)
        .map(BusinessTask::payloadJson)
        .map(this::readImageAnalysisPayload)
        .orElse(null);
  }

  private DraftVersion latestCorrectionVersion(long draftId) {
    return jdbcTemplate
        .query(
            """
            SELECT public_id, version_no, change_type, title, situation, analysis,
                   rectification, image_file_ids_json, created_at, created_by
              FROM report_draft_version
             WHERE draft_id = ? AND change_type = 'CORRECTION'
             ORDER BY version_no DESC, id DESC
             LIMIT 1
            """,
            (rs, row) ->
                new DraftVersion(
                    rs.getString("public_id"),
                    rs.getInt("version_no"),
                    rs.getString("change_type"),
                    new ReportSections(
                        rs.getString("title"),
                        rs.getString("situation"),
                        rs.getString("analysis"),
                        rs.getString("rectification")),
                    readStringList(rs.getString("image_file_ids_json")),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getString("created_by")),
            draftId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private DraftVersion previousVersion(long draftId, int version) {
    return jdbcTemplate
        .query(
            """
            SELECT public_id, version_no, change_type, title, situation, analysis,
                   rectification, image_file_ids_json, created_at, created_by
              FROM report_draft_version
             WHERE draft_id = ? AND version_no < ?
             ORDER BY version_no DESC, id DESC
             LIMIT 1
            """,
            (rs, row) ->
                new DraftVersion(
                    rs.getString("public_id"),
                    rs.getInt("version_no"),
                    rs.getString("change_type"),
                    new ReportSections(
                        rs.getString("title"),
                        rs.getString("situation"),
                        rs.getString("analysis"),
                        rs.getString("rectification")),
                    readStringList(rs.getString("image_file_ids_json")),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getString("created_by")),
            draftId,
            version)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private boolean hasMessagesSince(long draftId, LocalDateTime since) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM report_draft_message WHERE draft_id = ? AND created_at >= ?",
            Integer.class,
            draftId,
            since);
    return count != null && count > 0;
  }

  private boolean sameSections(ReportSections first, ReportSections second) {
    return java.util.Objects.equals(first.title(), second.title())
        && java.util.Objects.equals(first.situation(), second.situation())
        && java.util.Objects.equals(first.analysis(), second.analysis())
        && java.util.Objects.equals(first.rectification(), second.rectification());
  }

  @Transactional
  public Draft edit(
      String publicId, ReportSections sections, long expectedVersion, CurrentUser actor) {
    return edit(publicId, sections, null, expectedVersion, actor);
  }

  @Transactional
  public Draft edit(
      String publicId,
      ReportSections sections,
      List<String> imageFileIds,
      long expectedVersion,
      CurrentUser actor) {
    Draft draft = find(publicId, actor);
    ensureEditable(draft);
    requireSections(sections);
    if (draft.entityVersion() != expectedVersion) {
      throw new ResourceConflictException("STALE_DRAFT_VERSION", "工作稿已变化，请刷新后重试");
    }
    List<String> nextImages =
        imageFileIds == null
            ? draft.currentImageFileIds()
            : draft.currentImageFileIds().stream().filter(imageFileIds::contains).toList();
    int nextVersion = draft.currentVersion() + 1;
    int updated =
        jdbcTemplate.update(
            """
            UPDATE report_draft
               SET title = ?, situation = ?, analysis = ?, rectification = ?,
                   current_image_file_ids_json = ?, current_version_no = ?, updated_at = CURRENT_TIMESTAMP(3),
                   updated_by = ?, version = version + 1
             WHERE id = ? AND version = ?
            """,
            sections.title(),
            sections.situation(),
            sections.analysis(),
            sections.rectification(),
            writeJson(nextImages),
            nextVersion,
            actor.username(),
            draft.id(),
            expectedVersion);
    if (updated != 1) {
      throw new ResourceConflictException("STALE_DRAFT_VERSION", "工作稿已变化，请刷新后重试");
    }
    bindImages(draft.id(), nextImages, List.of(), actor.username());
    saveVersion(draft.id(), nextVersion, "MANUAL", sections, nextImages, actor.username());
    return find(publicId, actor);
  }

  @Transactional
  public void completeImageAnalysisTask(
      String publicId, String instruction, List<String> imageFileIds, String traceId, String actor) {
    Draft draft = loadDraft(publicId);
    if (!"AI_ANALYZING".equals(draft.analysisStatus())) {
      return;
    }
    ImageAnalysisTaskPayload payload = readImageAnalysisPayloadByTaskId(traceId);
    if (payload != null) {
      if (draft.analysisTaskId() == null || !draft.analysisTaskId().equals(traceId)) {
        return;
      }
      if (payload.contentVersion() != null && payload.contentVersion() != draft.currentVersion()) {
        markImageAnalysisFailed(draft.id(), "STALE_DRAFT_VERSION", actor);
        return;
      }
    }
    ReportSections layoutSnapshot =
        payload == null || payload.layoutSections() == null ? draft.sections() : payload.layoutSections();
    List<String> reportImageIds = imageFileIds == null ? draft.currentImageFileIds() : imageFileIds;
    try {
      validateAnalysisImages(reportImageIds, actor);
      List<AnalysisImageInput> modelInputs =
          analysisImageInputs(draft, reportImageIds, actor, false);
      List<AiImage> images = modelInputs.stream().map(AnalysisImageInput::image).toList();
      List<Fact> facts = factsForIntent(draft, "IMAGE_ANALYSIS");
      AgentContext context = agentContext(draft, "IMAGE_ANALYSIS");
      var result =
          aiServiceClient.assist(
              UUID.randomUUID().toString(),
              "IMAGE_ANALYSIS",
              requireInstruction(instruction),
              draft.sections(),
              facts,
              images,
              context,
              traceId == null ? "" : traceId);
      ReportSections updated = result.updatedSections();
      if (updated == null) {
        throw new AiServiceException("AI_RESPONSE_INCOMPLETE", "AI 未返回完整报告正文", false);
      }
      updated = mergeAiTextWithLayoutSnapshot(layoutSnapshot, updated);
      updated = sanitizeAiInlineImages(updated, reportImageIds);
      updated = normalizeImageCaptionPositions(updated);
      updated = normalizeAnalysisReasonPosition(updated);
      updated = normalizeAiReportText(updated);
      updated = normalizePositionRatedPowerFormula(draft, updated);
      int version = draft.currentVersion() + 1;
      updateDraft(
          draft.id(),
          updated,
          reportImageIds,
          version,
          result.initialReason(),
          result.finalReason(),
          actor,
          draft.entityVersion());
      bindImages(
          draft.id(),
          reportImageIds,
          expandGroupedImageAnalyses(reportImageIds, modelInputs, result.imageAnalyses()),
          actor);
      saveVersion(draft.id(), version, "IMAGE_ANALYSIS", updated, reportImageIds, actor);
      saveAnalysisRun(
          draft,
          null,
          modelInputs.size(),
          context);
      markImageAnalysisSucceeded(draft.id(), actor);
    } catch (RuntimeException exception) {
      markImageAnalysisFailed(draft.id(), analysisErrorCode(exception), actor);
      throw exception;
    }
  }

  @Transactional(readOnly = true)
  public List<DraftVersion> versions(String publicId, CurrentUser actor) {
    Draft draft = find(publicId, actor);
    return jdbcTemplate.query(
        """
        SELECT public_id, version_no, change_type, title, situation, analysis,
               rectification, image_file_ids_json, created_at, created_by
          FROM report_draft_version
         WHERE draft_id = ?
         ORDER BY version_no, id
        """,
        (rs, row) ->
            new DraftVersion(
                rs.getString("public_id"),
                rs.getInt("version_no"),
                rs.getString("change_type"),
                new ReportSections(
                    rs.getString("title"),
                    rs.getString("situation"),
                    rs.getString("analysis"),
                    rs.getString("rectification")),
                readStringList(rs.getString("image_file_ids_json")),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getString("created_by")),
        draft.id());
  }

  @Transactional
  public Draft restore(
      String publicId, String versionPublicId, long expectedVersion, CurrentUser actor) {
    Draft draft = find(publicId, actor);
    ensureEditable(draft);
    requireExpectedVersion(draft, expectedVersion);
    DraftVersion source =
        jdbcTemplate
            .query(
                """
                SELECT public_id, version_no, change_type, title, situation, analysis,
                       rectification, image_file_ids_json, created_at, created_by
                  FROM report_draft_version
                 WHERE draft_id = ? AND public_id = ?
                """,
                (rs, row) ->
                    new DraftVersion(
                        rs.getString("public_id"),
                        rs.getInt("version_no"),
                        rs.getString("change_type"),
                        new ReportSections(
                            rs.getString("title"),
                            rs.getString("situation"),
                            rs.getString("analysis"),
                            rs.getString("rectification")),
                        readStringList(rs.getString("image_file_ids_json")),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getString("created_by")),
                draft.id(),
                versionPublicId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("草稿版本不存在"));
    int nextVersion = draft.currentVersion() + 1;
    updateDraft(
        draft.id(),
        source.sections(),
        source.imageFileIds(),
        nextVersion,
        "",
        "",
        actor.username(),
        expectedVersion);
    saveVersion(
        draft.id(),
        nextVersion,
        "RESTORE",
        source.sections(),
        source.imageFileIds(),
        actor.username());
    return find(publicId, actor);
  }

  @Transactional
  public UploadedImage uploadImage(String publicId, MultipartFile image, CurrentUser actor) {
    Draft draft = find(publicId, actor);
    ensureEditable(draft);
    var stored =
        storedFileService.storeUpload(
            image, java.util.Set.of("png", "jpg", "jpeg"), "DRAFT_IMAGE", actor.username());
    List<String> nextImages =
        Stream.concat(draft.currentImageFileIds().stream(), Stream.of(stored.publicId()))
            .distinct()
            .toList();
    try {
      int updated =
          jdbcTemplate.update(
              """
              UPDATE report_draft
                 SET current_image_file_ids_json=?, updated_at=CURRENT_TIMESTAMP(3),
                     updated_by=?, version=version+1
               WHERE id=? AND version=? AND status IN ('DRAFT','CORRECTING','AI_COMPLETED','AI_FAILED')
              """,
              writeJson(nextImages),
              actor.username(),
              draft.id(),
              draft.entityVersion());
      if (updated != 1) {
        throw new ResourceConflictException("STALE_DRAFT_VERSION", "工作稿已变化，请刷新后重试");
      }
      bindImages(draft.id(), nextImages, List.of(), actor.username());
      return new UploadedImage(stored.publicId(), draft.entityVersion() + 1);
    } catch (RuntimeException exception) {
      storedFileService.deleteGenerated(stored);
      throw exception;
    }
  }

  @Transactional
  public Draft removeImage(
      String publicId, String imageFileId, long expectedVersion, CurrentUser actor) {
    Draft draft = find(publicId, actor);
    ensureEditable(draft);
    requireExpectedVersion(draft, expectedVersion);
    if (!draft.currentImageFileIds().contains(imageFileId)) {
      throw new ResourceNotFoundException("报告图片不存在");
    }
    List<String> nextImages =
        draft.currentImageFileIds().stream().filter(id -> !id.equals(imageFileId)).toList();
    ReportSections nextSections = removeInlineImage(draft.sections(), imageFileId);
    int nextVersion = draft.currentVersion() + 1;
    int updated =
        jdbcTemplate.update(
            """
            UPDATE report_draft
               SET title=?, situation=?, analysis=?, rectification=?,
                   current_image_file_ids_json=?, current_version_no=?,
                   updated_at=CURRENT_TIMESTAMP(3), updated_by=?, version=version+1
             WHERE id=? AND version=? AND status IN ('DRAFT','CORRECTING','AI_COMPLETED','AI_FAILED')
            """,
            nextSections.title(),
            nextSections.situation(),
            nextSections.analysis(),
            nextSections.rectification(),
            writeJson(nextImages),
            nextVersion,
            actor.username(),
            draft.id(),
            expectedVersion);
    if (updated != 1) {
      throw new ResourceConflictException("STALE_DRAFT_VERSION", "工作稿已变化，请刷新后重试");
    }
    var file = storedFileService.find(imageFileId);
    jdbcTemplate.update(
        "DELETE FROM report_draft_image WHERE draft_id=? AND file_id=?", draft.id(), file.id());
    bindImages(draft.id(), nextImages, List.of(), actor.username());
    saveVersion(draft.id(), nextVersion, "MANUAL", nextSections, nextImages, actor.username());
    return find(publicId, actor);
  }

  private ReportSections removeInlineImage(ReportSections sections, String imageFileId) {
    return new ReportSections(
        removeInlineImage(sections.title(), imageFileId),
        removeInlineImage(sections.situation(), imageFileId),
        removeInlineImage(sections.analysis(), imageFileId),
        removeInlineImage(sections.rectification(), imageFileId));
  }

  private String removeInlineImage(String content, String imageFileId) {
    if (content == null || content.isBlank()) {
      return content;
    }
    String quotedId = Pattern.quote(imageFileId);
    return content
        .replaceAll(
            "(?is)<figure\\b[^>]*data-file-id=[\"']" + quotedId + "[\"'][^>]*>.*?</figure>", "")
        .replaceAll("(?is)<img\\b[^>]*data-file-id=[\"']" + quotedId + "[\"'][^>]*>", "")
        .replaceAll(
            "(?is)<div\\b([^>]*class=[\"'][^\"']*inline-image-row[^\"']*[\"'][^>]*)>\\s*</div>",
            "")
        .trim();
  }

  @Transactional
  public Draft reorderImages(
      String publicId, List<String> orderedIds, long expectedVersion, CurrentUser actor) {
    Draft draft = find(publicId, actor);
    ensureEditable(draft);
    requireExpectedVersion(draft, expectedVersion);
    List<String> safeIds = orderedIds == null ? List.of() : List.copyOf(orderedIds);
    if (safeIds.size() != draft.currentImageFileIds().size()
        || safeIds.stream().distinct().count() != safeIds.size()
        || !new java.util.HashSet<>(safeIds)
            .equals(new java.util.HashSet<>(draft.currentImageFileIds()))) {
      throw new BusinessRuleException("REPORT_IMAGE_ORDER_INVALID", "图片顺序必须包含当前报告的全部图片");
    }
    int nextVersion = draft.currentVersion() + 1;
    int updated =
        jdbcTemplate.update(
            """
            UPDATE report_draft
               SET current_image_file_ids_json=?, current_version_no=?,
                   updated_at=CURRENT_TIMESTAMP(3), updated_by=?, version=version+1
             WHERE id=? AND version=? AND status IN ('DRAFT','CORRECTING','AI_COMPLETED','AI_FAILED')
            """,
            writeJson(safeIds),
            nextVersion,
            actor.username(),
            draft.id(),
            expectedVersion);
    if (updated != 1) {
      throw new ResourceConflictException("STALE_DRAFT_VERSION", "工作稿已变化，请刷新后重试");
    }
    bindImages(draft.id(), safeIds, List.of(), actor.username());
    saveVersion(draft.id(), nextVersion, "MANUAL", draft.sections(), safeIds, actor.username());
    return find(publicId, actor);
  }

  @Transactional
  public BusinessTask submitFormal(String publicId, long expectedVersion, CurrentUser actor) {
    Draft draft = find(publicId, actor);
    String businessKey = formalTaskBusinessKey(draft.publicId(), draft.currentVersion());
    var existing = taskRepository.findByTypeAndBusinessKey(TaskType.FORMAL_REPORT, businessKey);
    if (existing.isPresent()) {
      BusinessTask task = existing.orElseThrow();
      if (task.status() == TaskStatus.FAILED || task.status() == TaskStatus.SUCCEEDED) {
        String payloadJson =
            writeJson(Map.of("draftId", publicId, "contentVersion", draft.currentVersion()));
        if (!taskRepository.requeueWithPayload(task.publicId(), payloadJson, actor.username())) {
          throw new ResourceConflictException("FORMAL_REPORT_REQUEUE_FAILED", "正式报告任务状态已变化，请刷新后重试");
        }
        return taskRepository.findByPublicId(task.publicId()).orElse(task);
      }
      return task;
    }
    ensureEditable(draft);
    requireExpectedVersion(draft, expectedVersion);
    int transitioned =
        jdbcTemplate.update(
            """
        UPDATE report_draft SET status = 'GENERATING', confirmed_at = CURRENT_TIMESTAMP(3),
               confirmed_by = ?, updated_at = CURRENT_TIMESTAMP(3),
               updated_by = ?, version = version + 1
         WHERE id = ? AND version = ? AND status IN ('DRAFT', 'CORRECTING', 'AI_COMPLETED', 'AI_FAILED')
        """,
            actor.username(),
            actor.username(),
            draft.id(),
            expectedVersion);
    if (transitioned != 1) {
      throw new ResourceConflictException("STALE_DRAFT_VERSION", "工作稿已变化，请刷新后重试");
    }
    try {
      return taskRepository.create(
          TaskType.FORMAL_REPORT,
          businessKey,
          writeJson(Map.of("draftId", publicId, "contentVersion", draft.currentVersion())),
          actor.username(),
          3);
    } catch (DuplicateKeyException exception) {
      return taskRepository
          .findByTypeAndBusinessKey(TaskType.FORMAL_REPORT, businessKey)
          .orElseThrow(() -> exception);
    }
  }

  static String formalTaskBusinessKey(String draftPublicId, int contentVersion) {
    return "FORMAL_REPORT:DRAFT:" + draftPublicId + ":CONTENT_VERSION:" + contentVersion;
  }

  private void validateAnalysisImages(List<String> imageIds, String actor) {
    if (imageIds == null || imageIds.isEmpty()) {
      throw new BusinessRuleException("AI_IMAGES_REQUIRED", "请先在报告正文中粘贴至少一张图片");
    }
    if (imageIds.stream().distinct().count() != imageIds.size()) {
      throw new BusinessRuleException("AI_IMAGES_INVALID", "图片列表存在重复图片");
    }
    for (String imageId : imageIds) {
      var file = storedFileService.find(imageId);
      requireFileOwner(actor, file.createdBy());
      if (!("image/png".equals(file.mediaType()) || "image/jpeg".equals(file.mediaType()))) {
        throw new BusinessRuleException("AI_IMAGE_TYPE_INVALID", "图片仅支持 PNG/JPEG");
      }
      if (file.byteSize() > 10L * 1024 * 1024) {
        throw new BusinessRuleException("AI_IMAGE_TOO_LARGE", "单张分析图片不能超过 10 MiB");
      }
    }
  }

  private void markImageAnalysisSucceeded(long draftId, String actor) {
    jdbcTemplate.update(
        """
        UPDATE report_draft
           SET analysis_status = 'AI_COMPLETED_PENDING_CONFIRMATION',
               analysis_error_code = NULL,
               analysis_completed_at = CURRENT_TIMESTAMP(3),
               updated_at = CURRENT_TIMESTAMP(3),
               updated_by = ?,
               version = version + 1
         WHERE id = ?
        """,
        actor,
        draftId);
  }

  public void markImageAnalysisFailed(String publicId, String errorCode, String actor) {
    Draft draft = loadDraft(publicId);
    markImageAnalysisFailed(draft.id(), errorCode, actor);
  }

  private void markImageAnalysisFailed(long draftId, String errorCode, String actor) {
    jdbcTemplate.update(
        """
        UPDATE report_draft
           SET analysis_status = 'AI_FAILED',
               analysis_error_code = ?,
               analysis_completed_at = CURRENT_TIMESTAMP(3),
               updated_at = CURRENT_TIMESTAMP(3),
               updated_by = ?,
               version = version + 1
         WHERE id = ?
        """,
        errorCode,
        actor,
        draftId);
  }

  private String analysisErrorCode(RuntimeException exception) {
    if (exception instanceof BusinessRuleException businessRule) {
      return businessRule.code();
    }
    if (exception instanceof AiServiceException) {
      return "AI_IMAGE_ANALYSIS_FAILED";
    }
    return "AI_IMAGE_ANALYSIS_FAILED";
  }

  private List<Fact> facts(Draft draft) {
    return jdbcTemplate
        .query(
            """
            SELECT c.name AS city_name, s.district_name,
                   m.resource_summary_json AS master_json,
                   a.audit_status, a.over_limit_type, a.max_ratio,
                   COALESCE(a.actual_total_kwh, a.actual_energy) AS actual_energy,
                   a.current_daily_avg_kwh,
                   COALESCE(a.actual_report_amount, a.actual_amount) AS actual_amount,
                   a.yoy_result, a.yoy_reference_period, a.yoy_reference_total_kwh,
                   COALESCE(a.yoy_exceed_ratio, a.yoy_ratio) AS yoy_ratio,
                   a.mom_result, a.mom_reference_period, a.mom_reference_total_kwh,
                   COALESCE(a.mom_exceed_ratio, a.mom_ratio) AS mom_ratio,
                   a.rated_result, COALESCE(a.rated_total_kwh, a.rated_benchmark_energy) AS rated_energy,
                   COALESCE(a.rated_exceed_ratio, a.rated_ratio) AS rated_ratio,
                   a.payment_count, a.payment_eligibility_reason, a.aggregated_payment_days,
                   COALESCE(m.resource_summary_json, s.data_json) AS data_json, a.detail_json
              FROM billing_point_snapshot s
              JOIN city c ON c.code = s.city_code
              LEFT JOIN billing_point_master m
                ON m.city_code = s.city_code
               AND m.billing_point_code = s.billing_point_code
              LEFT JOIN audit_result a
                ON a.billing_point_code = s.billing_point_code
               AND a.data_period = s.data_period
               AND a.city_code = s.city_code
             WHERE s.public_id = ?
            """,
            (rs, row) -> {
              var values = new java.util.ArrayList<Fact>();
              values.add(new Fact("报账点编码", draft.billingPointCode()));
              values.add(new Fact("报账点名称", draft.billingPointName()));
              values.add(new Fact("所属城市", value(rs.getString("city_name"))));
              values.add(
                  new Fact(
                      "所属区县",
                      value(
                          firstNonBlank(
                              jsonLikeValue(rs.getString("master_json"), "所属区县"),
                              rs.getString("district_name")))));
              values.add(new Fact("账期", draft.period()));
              values.add(new Fact("稽核状态", value(rs.getString("audit_status"))));
              values.add(new Fact("超标类型", value(rs.getString("over_limit_type"))));
              values.add(new Fact("实际总用电量", value(rs.getBigDecimal("actual_energy"))));
              values.add(new Fact("日均用电量", value(rs.getBigDecimal("current_daily_avg_kwh"))));
              values.add(new Fact("实际报账金额", value(rs.getBigDecimal("actual_amount"))));
              values.add(new Fact("同比结果", value(rs.getString("yoy_result"))));
              values.add(new Fact("同比参考账期", value(rs.getString("yoy_reference_period"))));
              values.add(new Fact("同比参考电量", value(rs.getBigDecimal("yoy_reference_total_kwh"))));
              values.add(new Fact("同比超标比例", value(rs.getBigDecimal("yoy_ratio"))));
              values.add(new Fact("环比结果", value(rs.getString("mom_result"))));
              values.add(new Fact("环比参考账期", value(rs.getString("mom_reference_period"))));
              values.add(new Fact("环比参考电量", value(rs.getBigDecimal("mom_reference_total_kwh"))));
              values.add(new Fact("环比超标比例", value(rs.getBigDecimal("mom_ratio"))));
              String ratedResult = rs.getString("rated_result");
              var ratedContext =
                  positionRatedPowerContext(
                      draft,
                      rs.getString("data_json"),
                      rs.getObject("aggregated_payment_days"),
                      rs.getBigDecimal("rated_energy"));
              values.add(new Fact("额定标杆结果", value(ratedResult)));
              values.add(new Fact("额定标杆电量", value(rs.getBigDecimal("rated_energy"))));
              values.add(new Fact("额定标杆超标比例", value(rs.getBigDecimal("rated_ratio"))));
              ratedContext.ifPresent(
                  context ->
                      values.add(new Fact("位置点额定功率标杆公式", context.factText())));
              if ("OVER_LIMIT".equals(ratedResult)) {
                values.add(
                    new Fact(
                        "额定超标重点排查方向",
                        "历史稽核报告中较常见原因为资管系统未及时更新、额定功率台账未及时更新、现场设备功率未纳入系统、直放站或设备功率信息缺失；生成报告时应优先围绕这些原因核对当前图片、设备数量、资管信息和额定功率材料，不要默认写待核实。"));
              }
              values.add(new Fact("缴费记录数", Integer.toString(rs.getInt("payment_count"))));
              values.add(new Fact("缴费核验说明", value(rs.getString("payment_eligibility_reason"))));
              values.add(new Fact("汇总缴费天数", value(rs.getObject("aggregated_payment_days"))));
              values.add(new Fact("报账点完整业务快照", compact(rs.getString("data_json"), 12_000)));
              values.add(new Fact("稽核计算明细", compact(rs.getString("detail_json"), 12_000)));
              return List.copyOf(values);
            },
            draft.billingPointPeriodId())
        .stream()
        .findFirst()
        .orElseGet(
            () ->
                List.of(
                    new Fact("报账点编码", draft.billingPointCode()),
                    new Fact("报账点名称", draft.billingPointName()),
                    new Fact("账期", draft.period())));
  }

  private List<Fact> factsForIntent(Draft draft, String intent) {
    List<Fact> allFacts = facts(draft);
    if ("IMAGE_ANALYSIS".equals(intent) || "INITIAL".equals(intent)) {
      return allFacts;
    }
    return allFacts.stream()
        .filter(fact -> !"报账点完整业务快照".equals(fact.fieldName()) && !"稽核计算明细".equals(fact.fieldName()))
        .toList();
  }

  private boolean isPositionPoint(Draft draft, String dataJson) {
    return value(draft.billingPointName()).contains("位置点")
        || value(draft.billingPointCode()).contains("位置点")
        || value(dataJson).contains("位置点");
  }

  private Optional<PositionRatedPowerContext> positionRatedPowerContext(
      Draft draft, String dataJson, Object aggregatedPaymentDays, Object ratedEnergy) {
    String resourceSummary =
        jdbcTemplate
            .query(
                """
                SELECT resource_summary_json
                  FROM billing_point_master
                 WHERE city_code = ?
                   AND billing_point_code = ?
                 LIMIT 1
                """,
                (rs, row) -> value(rs.getString("resource_summary_json")),
                draft.cityCode(),
                draft.billingPointCode())
            .stream()
            .findFirst()
            .orElse("");
    String combined = value(dataJson) + " " + resourceSummary;
    if (!isPositionPoint(draft, combined)) {
      return Optional.empty();
    }
    int days = periodDays(draft.period(), aggregatedPaymentDays);
    int month = periodMonth(draft.period());
    boolean summer = month >= 5 && month <= 10;
    String mainPower = firstNonBlank(jsonLikeValue(resourceSummary, "主设备功率"), jsonLikeValue(combined, "主设备总额定功率"));
    String airPower = firstNonBlank(jsonLikeValue(resourceSummary, "空调总功率"), jsonLikeValue(combined, "空调总额定功率"));
    String towerAirPower = jsonLikeValue(resourceSummary, "铁塔空调总额定功率");
    String ratedTotal = decimalText(ratedEnergy);
    return Optional.of(
        new PositionRatedPowerContext(
            summer, days, mainPower, airPower, towerAirPower, ratedTotal));
  }

  private int periodMonth(String period) {
    try {
      return YearMonth.parse(period).getMonthValue();
    } catch (RuntimeException exception) {
      return 0;
    }
  }

  private int periodDays(String period, Object aggregatedPaymentDays) {
    if (aggregatedPaymentDays instanceof Number number && number.intValue() > 0) {
      return number.intValue();
    }
    try {
      return YearMonth.parse(period).lengthOfMonth();
    } catch (RuntimeException exception) {
      return 30;
    }
  }

  private AgentContext agentContext(Draft draft, String intent) {
    boolean needsHistoricalCases = "INITIAL".equals(intent) || "IMAGE_ANALYSIS".equals(intent);
    boolean needsCityMemory = needsHistoricalCases || "CORRECTION".equals(intent);
    var samePoint = new java.util.ArrayList<Reference>();
    if (needsHistoricalCases) {
      var profile = cityMemoryService.findPointProfile(draft.cityCode(), draft.billingPointCode());
      if (profile != null) {
        samePoint.add(
            new Reference("PROFILE-" + profile.publicId(), profile.summary(), profile.cityCode()));
      }
      samePoint.addAll(
          reportReferences(
              "s.city_code = ? AND s.billing_point_code = ? AND s.data_period <> ?",
              Math.min(3, maxHistoryCases),
              draft.cityCode(),
              draft.billingPointCode(),
              draft.period()));
    }
    var cityReferences = new java.util.ArrayList<Reference>();
    if (needsCityMemory) {
      cityReferences.addAll(relevantCityMemoryReferences(draft));
    }
    if (needsHistoricalCases) {
      cityReferences.addAll(
          reportReferences(
              "s.city_code = ? AND s.billing_point_code <> ? AND (a.over_limit_type = ? OR ? IS NULL)",
              Math.min(3, maxHistoryCases),
              draft.cityCode(),
              draft.billingPointCode(),
              draft.overLimitType(),
              draft.overLimitType()));
    }
    var provinceReferences = new java.util.ArrayList<Reference>();
    if (needsHistoricalCases) {
      provinceReferences.addAll(
          externalProvinceReportReferences(
              "s.city_code <> ? AND (a.over_limit_type = ? OR ? IS NULL)",
              Math.min(3, maxHistoryCases),
              draft.cityCode(),
              draft.overLimitType(),
              draft.overLimitType()));
    }
    List<Reference> imageEvidence = imageEvidenceReferences(draft.id());
    List<ConversationTurn> messages =
        jdbcTemplate
            .query(
                """
            SELECT user_content, assistant_content
              FROM (
                SELECT id, user_content, assistant_content, created_at
                  FROM report_draft_message
                 WHERE draft_id = ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT 10
              ) recent
             ORDER BY created_at, id
            """,
                (rs, row) ->
                    List.of(
                        new ConversationTurn("用户", compact(rs.getString("user_content"), 2000)),
                        new ConversationTurn(
                            "AI", compact(rs.getString("assistant_content"), 3000))),
                draft.id())
            .stream()
            .flatMap(List::stream)
            .toList();
    validateCurrentCityContext(draft.cityCode(), samePoint, cityReferences);
    validateProvinceReferences(draft.cityCode(), provinceReferences);
    return new AgentContext(
        List.copyOf(samePoint),
        List.copyOf(cityReferences),
        List.copyOf(provinceReferences),
        imageEvidence,
        messages);
  }

  private List<Reference> imageEvidenceReferences(long draftId) {
    return jdbcTemplate.query(
        """
        SELECT i.sort_no, i.analysis_json
          FROM report_draft_image i
         WHERE i.draft_id = ? AND i.analysis_json IS NOT NULL
         ORDER BY i.sort_no, i.id
        """,
        (rs, row) ->
            new Reference(
                "IMG-" + (rs.getInt("sort_no") + 1), compact(rs.getString("analysis_json"), 3000)),
        draftId);
  }

  List<Reference> cityMemoryReferences(String cityCode) {
    return cityMemoryService
        .findRelevantMemories(new MemoryQuery(cityCode, null, null, null, null), maxHistoryCases)
        .stream()
        .map(
            memory ->
                new Reference("MEM-" + memory.publicId(), memory.summary(), memory.cityCode()))
        .toList();
  }

  private List<Reference> relevantCityMemoryReferences(Draft draft) {
    return cityMemoryService
        .findRelevantMemories(
            new MemoryQuery(
                draft.cityCode(),
                draft.billingPointCode(),
                draft.overLimitType(),
                draft.period(),
                draft.maxExceedRatio(),
                currentImageEvidenceText(draft.id())),
            Math.min(5, maxHistoryCases))
        .stream()
        .map(
            memory ->
                new Reference(
                    "MEM-" + memory.publicId(),
                    "相关度=" + memory.score() + "；" + memory.summary(),
                    memory.cityCode()))
        .toList();
  }

  private String currentImageEvidenceText(long draftId) {
    return jdbcTemplate
        .queryForList(
            """
            SELECT analysis_json FROM report_draft_image
             WHERE draft_id=? AND analysis_json IS NOT NULL
             ORDER BY sort_no, id
            """,
            String.class,
            draftId)
        .stream()
        .map(value -> compact(value, 1000))
        .reduce((left, right) -> left + "；" + right)
        .orElse("");
  }

  private void validateCurrentCityContext(
      String expectedCityCode, List<Reference> samePoint, List<Reference> cityReferences) {
    boolean crossCity =
        Stream.concat(samePoint.stream(), cityReferences.stream())
            .map(Reference::cityCode)
            .filter(java.util.Objects::nonNull)
            .anyMatch(cityCode -> !expectedCityCode.equals(cityCode));
    if (crossCity) {
      throw new AiServiceException("AI_CONTEXT_CITY_SCOPE_VIOLATION", "检测到跨城市记忆，已停止模型调用", false);
    }
  }

  private void validateProvinceReferences(
      String expectedCityCode, List<Reference> provinceReferences) {
    boolean currentCityMixedIn =
        provinceReferences.stream()
            .map(Reference::cityCode)
            .filter(java.util.Objects::nonNull)
            .anyMatch(expectedCityCode::equals);
    if (currentCityMixedIn) {
      throw new AiServiceException("AI_CONTEXT_CITY_SCOPE_VIOLATION", "检测到外市参考混入本市案例，已停止模型调用", false);
    }
  }

  private List<Reference> reportReferences(String predicate, int limit, Object... args) {
    if (limit <= 0) {
      return List.of();
    }
    var parameters = new java.util.ArrayList<>(List.of(args));
    parameters.add(limit);
    return jdbcTemplate.query(
        """
        SELECT r.public_id, s.city_code, s.billing_point_code, s.data_period,
               r.title, r.situation, r.analysis, r.rectification,
               a.over_limit_type, hc.image_count, hc.image_analysis_status,
               hc.image_analysis_text, hc.final_reason, hc.summary AS historical_summary
          FROM audit_report r
          JOIN billing_point_snapshot s ON s.id = r.billing_point_snapshot_id
          LEFT JOIN historical_audit_case hc ON hc.report_id = r.id
          LEFT JOIN audit_result a
            ON a.billing_point_code = s.billing_point_code
           AND a.data_period = s.data_period
           AND a.city_code = s.city_code
         WHERE
        """
            + predicate
            + " ORDER BY r.generated_at DESC, r.id DESC LIMIT ?",
        (rs, row) -> {
          String situation = value(rs.getString("situation"));
          String analysis = value(rs.getString("analysis"));
          String rectification = value(rs.getString("rectification"));
          String historicalSummary = value(rs.getString("historical_summary"));
          String imageAnalysisText = value(rs.getString("image_analysis_text"));
          String equipmentStyleExamples =
              historicalEquipmentStyleExamples(
                  situation
                      + "。"
                      + analysis
                      + "。"
                      + rectification
                      + "。"
                      + historicalSummary
                      + "。"
                      + imageAnalysisText);
          String reasonSummary =
              firstNonBlank(
                  value(rs.getString("final_reason")),
                  historicalReasonSummary(situation + "。" + analysis + "。" + rectification + "。" + historicalSummary));
          return new Reference(
              "CASE-" + rs.getString("public_id"),
              compact(
                      "城市="
                          + rs.getString("city_code")
                          + "；报账点="
                          + rs.getString("billing_point_code")
                          + "；账期="
                          + rs.getString("data_period")
                          + "；超标类型="
                          + value(rs.getString("over_limit_type"))
                          + "；历史明确原因="
                          + reasonSummary
                          + "；历史原因类型="
                          + historicalReasonTypes(reasonSummary + "。" + historicalSummary)
                          + (equipmentStyleExamples.isBlank()
                              ? ""
                              : "；历史图片说明写法=" + equipmentStyleExamples)
                          + "；标题="
                          + value(rs.getString("title"))
                          + "；情况="
                          + situation
                          + "；分析="
                          + analysis
                          + "；整改="
                          + rectification,
                      6000)
                  + compact(
                      "；历史图片数="
                          + rs.getInt("image_count")
                          + "；历史图片分析状态="
                          + value(rs.getString("image_analysis_status"))
                          + "；历史图片证据="
                          + imageAnalysisText,
                      6000),
              rs.getString("city_code"));
        },
        parameters.toArray());
  }

  private List<Reference> externalProvinceReportReferences(
      String predicate, int limit, Object... args) {
    return reportReferences(predicate, limit, args).stream()
        .map(
            reference ->
                new Reference(
                    "OUTCITY-" + reference.id(),
                    "外市参考：城市=" + value(reference.cityCode()) + "；" + reference.summary(),
                    reference.cityCode()))
        .toList();
  }

  private long saveMessage(
      Draft draft,
      String intent,
      String userContent,
      String assistantContent,
      boolean changed,
      List<String> imageIds,
      String initialReason,
      String finalReason,
      int modelImageCount,
      AgentContext context,
      String actor) {
    String messagePublicId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO report_draft_message
          (public_id, draft_id, city_code, intent, user_content, assistant_content,
           changed_draft, image_file_ids_json, initial_reason, final_reason, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        messagePublicId,
        draft.id(),
        draft.cityCode(),
        intent,
        userContent,
        assistantContent,
        changed,
        writeJson(imageIds),
        blankToNull(initialReason),
        blankToNull(finalReason),
        actor);
    Long messageId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM report_draft_message WHERE public_id = ?", Long.class, messagePublicId);
    if (messageId == null) {
      throw new IllegalStateException("AI message key was not generated");
    }
    jdbcTemplate.update(
        """
        INSERT INTO ai_analysis_run
          (public_id, draft_id, message_id, city_code, billing_point_code, model_name,
           prompt_version, image_count, retrieved_memory_ids_json, context_summary_json,
           status, completed_at)
        VALUES (?, ?, ?, ?, ?, ?, 'three-fees-agent-v4', ?, ?, ?,
                'SUCCEEDED', CURRENT_TIMESTAMP(3))
        """,
        aiAnalysisRunArgs(draft, messageId, modelImageCount, context));
    return messageId;
  }

  private void saveAnalysisRun(
      Draft draft, Long messageId, int modelImageCount, AgentContext context) {
    jdbcTemplate.update(
        """
        INSERT INTO ai_analysis_run
          (public_id, draft_id, message_id, city_code, billing_point_code, model_name,
           prompt_version, image_count, retrieved_memory_ids_json, context_summary_json,
           status, completed_at)
        VALUES (?, ?, ?, ?, ?, ?, 'three-fees-agent-v4', ?, ?, ?,
                'SUCCEEDED', CURRENT_TIMESTAMP(3))
        """,
        aiAnalysisRunArgs(draft, messageId, modelImageCount, context));
  }

  private Object[] aiAnalysisRunArgs(
      Draft draft, Long messageId, int modelImageCount, AgentContext context) {
    return new Object[] {
      UUID.randomUUID().toString(),
      draft.id(),
      messageId,
      draft.cityCode(),
      draft.billingPointCode(),
      aiServiceClient.modelName(),
      modelImageCount,
      writeJson(
          Stream.concat(context.cityMemories().stream(), context.provinceReferences().stream())
              .map(Reference::id)
              .toList()),
      writeJson(
          Map.of(
              "samePointReferenceCount", context.samePointCases().size(),
              "cityReferenceCount", context.cityMemories().size(),
              "provinceReferenceCount", context.provinceReferences().size(),
              "imageEvidenceCount", context.imageEvidence().size(),
              "conversationTurnCount", context.recentMessages().size(),
              "modelImageCount", modelImageCount))
    };
  }

  private void saveVersion(
      long draftId,
      int version,
      String changeType,
      ReportSections sections,
      List<String> imageIds,
      String actor) {
    jdbcTemplate.update(
        """
        INSERT INTO report_draft_version
          (public_id, draft_id, version_no, change_type, title, situation, analysis,
           rectification, image_file_ids_json, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID().toString(),
        draftId,
        version,
        changeType,
        sections.title(),
        sections.situation(),
        sections.analysis(),
        sections.rectification(),
        writeJson(imageIds),
        actor);
  }

  private List<AnalysisImageInput> analysisImageInputs(
      Draft draft, List<String> reportImageIds, String actor, boolean validateOwner) {
    if (reportImageIds == null || reportImageIds.isEmpty()) {
      return List.of();
    }
    var requestedIds = new java.util.LinkedHashSet<>(reportImageIds);
    var inputs = new ArrayList<AnalysisImageInput>();
    var consumed = new java.util.LinkedHashSet<String>();
    String html = draft.sections().title()
        + draft.sections().situation()
        + draft.sections().analysis()
        + draft.sections().rectification();
    var matcher = Pattern.compile(
            "(?is)<div\\b[^>]*class=[\"'][^\"']*inline-image-row[^\"']*[\"'][^>]*>.*?</div>"
                + "|<figure\\b[^>]*data-file-id=[\"'][^\"']+[\"'][^>]*>.*?</figure>")
        .matcher(html);
    while (matcher.find()) {
      String fragment = matcher.group();
      List<String> ids = inlineFileIdsInOrder(fragment).stream()
          .filter(requestedIds::contains)
          .filter(id -> !consumed.contains(id))
          .toList();
      if (ids.isEmpty()) {
        continue;
      }
      inputs.add(analysisImageInput(ids, actor, validateOwner));
      consumed.addAll(ids);
    }
    for (String id : requestedIds) {
      if (!consumed.contains(id)) {
        inputs.add(analysisImageInput(List.of(id), actor, validateOwner));
      }
    }
    return inputs;
  }

  private AnalysisImageInput analysisImageInput(
      List<String> imageIds, String actor, boolean validateOwner) {
    var files = new ArrayList<StoredFile>();
    var bytes = new ArrayList<byte[]>();
    for (String imageId : imageIds) {
      var file = storedFileService.find(imageId);
      if (validateOwner) {
        requireFileOwner(actor, file.createdBy());
      }
      if (!("image/png".equals(file.mediaType()) || "image/jpeg".equals(file.mediaType()))) {
        throw new BusinessRuleException("AI_IMAGE_TYPE_INVALID", "图片仅支持 PNG/JPEG");
      }
      if (file.byteSize() > 10L * 1024 * 1024) {
        throw new BusinessRuleException("AI_IMAGE_TOO_LARGE", "单张分析图片不能超过 10 MiB");
      }
      files.add(file);
      bytes.add(storedFileService.readBytes(file));
    }
    if (imageIds.size() == 1) {
      StoredFile file = files.getFirst();
      return new AnalysisImageInput(
          new AiImage(file.originalName(), file.mediaType(), bytes.getFirst()), imageIds);
    }
    return new AnalysisImageInput(
        new AiImage("并排图片组-" + String.join("-", imageIds) + ".png", "image/png",
            composeInlineImageRow(bytes)),
        imageIds);
  }

  private byte[] composeInlineImageRow(List<byte[]> images) {
    try {
      var decoded = new ArrayList<BufferedImage>();
      for (byte[] image : images) {
        BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(image));
        if (buffered == null || buffered.getWidth() <= 0 || buffered.getHeight() <= 0) {
          throw new BusinessRuleException("AI_IMAGE_TYPE_INVALID", "图片无法解析");
        }
        decoded.add(buffered);
      }
      int gap = 18;
      int maxHeight = 900;
      int totalWidth = gap * Math.max(0, decoded.size() - 1);
      int rowHeight = 1;
      var widths = new ArrayList<Integer>();
      var heights = new ArrayList<Integer>();
      for (BufferedImage image : decoded) {
        double scale = Math.min(1.0, (double) maxHeight / image.getHeight());
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        widths.add(width);
        heights.add(height);
        totalWidth += width;
        rowHeight = Math.max(rowHeight, height);
      }
      int maxWidth = 2400;
      double rowScale = Math.min(1.0, (double) maxWidth / Math.max(1, totalWidth));
      int canvasWidth = Math.max(1, (int) Math.round(totalWidth * rowScale));
      int canvasHeight = Math.max(1, (int) Math.round(rowHeight * rowScale));
      BufferedImage composed = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
      Graphics2D graphics = composed.createGraphics();
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, canvasWidth, canvasHeight);
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      int x = 0;
      for (int index = 0; index < decoded.size(); index++) {
        int width = Math.max(1, (int) Math.round(widths.get(index) * rowScale));
        int height = Math.max(1, (int) Math.round(heights.get(index) * rowScale));
        graphics.drawImage(decoded.get(index), x, 0, width, height, null);
        x += width + (int) Math.round(gap * rowScale);
      }
      graphics.dispose();
      var output = new ByteArrayOutputStream();
      ImageIO.write(composed, "png", output);
      return output.toByteArray();
    } catch (IOException exception) {
      throw new BusinessRuleException("AI_IMAGE_TYPE_INVALID", "图片无法解析");
    }
  }

  private List<ImageAnalysis> expandGroupedImageAnalyses(
      List<String> reportImageIds,
      List<AnalysisImageInput> modelInputs,
      List<ImageAnalysis> analyses) {
    if (analyses == null || analyses.isEmpty()) {
      return List.of();
    }
    var expanded = new ArrayList<ImageAnalysis>();
    for (int modelIndex = 0; modelIndex < modelInputs.size(); modelIndex++) {
      int imageNumber = modelIndex + 1;
      ImageAnalysis analysis = analyses.stream()
          .filter(value -> ("IMG-" + imageNumber).equals(value.imageId()))
          .findFirst()
          .orElse(null);
      if (analysis == null) {
        continue;
      }
      for (String fileId : modelInputs.get(modelIndex).fileIds()) {
        int reportIndex = reportImageIds.indexOf(fileId);
        if (reportIndex >= 0) {
          expanded.add(withImageId(analysis, reportIndex + 1));
        }
      }
    }
    return expanded;
  }

  private void bindImages(
      long draftId, List<String> imageIds, List<ImageAnalysis> analyses, String actor) {
    for (int index = 0; index < imageIds.size(); index++) {
      var file = storedFileService.find(imageIds.get(index));
      int imageNumber = index + 1;
      ImageAnalysis analysis =
          analyses == null
              ? null
              : analyses.stream()
                  .filter(value -> ("IMG-" + imageNumber).equals(value.imageId()))
                  .findFirst()
                  .orElse(null);
      String analysisJson =
          analysis == null
              ? normalizedStoredImageAnalysis(draftId, file.id(), imageNumber)
              : writeJson(withImageId(analysis, imageNumber));
      jdbcTemplate.update(
          """
          INSERT INTO report_draft_image
            (public_id, draft_id, file_id, sort_no, source_type, analysis_json, created_by)
          VALUES (?, ?, ?, ?, 'PASTE', ?, ?)
          ON DUPLICATE KEY UPDATE sort_no=VALUES(sort_no),
                                  analysis_json=COALESCE(VALUES(analysis_json), analysis_json)
          """,
          UUID.randomUUID().toString(),
          draftId,
          file.id(),
          index,
          analysisJson,
          actor);
    }
  }

  private String normalizedStoredImageAnalysis(long draftId, long fileId, int imageNumber) {
    List<String> payloads =
        jdbcTemplate.query(
            "SELECT analysis_json FROM report_draft_image WHERE draft_id=? AND file_id=?",
            (rs, row) -> rs.getString("analysis_json"),
            draftId,
            fileId);
    String payload = payloads.isEmpty() ? null : payloads.getFirst();
    if (payload == null || payload.isBlank()) {
      return null;
    }
    try {
      return writeJson(withImageId(readImageAnalysis(payload), imageNumber));
    } catch (JacksonException exception) {
      try {
        String unwrapped = objectMapper.readValue(payload, String.class);
        return writeJson(withImageId(readImageAnalysis(unwrapped), imageNumber));
      } catch (JacksonException nestedException) {
        LOGGER.warn(
            "Persisted draft image analysis could not be renumbered: draftId={}, fileId={}",
            draftId,
            fileId);
        return payload;
      }
    }
  }

  private ImageAnalysis readImageAnalysis(String payload) throws JacksonException {
    return objectMapper.readValue(payload, ImageAnalysis.class);
  }

  private ImageAnalysis withImageId(ImageAnalysis analysis, int imageNumber) {
    return new ImageAnalysis(
        "IMG-" + imageNumber,
        analysis.category(),
        analysis.observation(),
        analysis.evidence(),
        analysis.limitation());
  }

  private ReportSections preserveInlineFigures(ReportSections original, ReportSections generated) {
    return new ReportSections(
        preserveInlineFigures(original.title(), generated.title()),
        preserveInlineFigures(original.situation(), generated.situation()),
        preserveInlineFigures(original.analysis(), generated.analysis()),
        preserveInlineFigures(original.rectification(), generated.rectification()));
  }

  private ReportSections mergeAiTextWithLayoutSnapshot(
      ReportSections layoutSnapshot, ReportSections generated) {
    return new ReportSections(
        firstNonBlank(stripInlineImages(generated.title()), layoutSnapshot.title()),
        preserveInlineFigures(layoutSnapshot.situation(), stripBase64Images(generated.situation())),
        preserveInlineFigures(layoutSnapshot.analysis(), stripBase64Images(generated.analysis())),
        preserveInlineFigures(layoutSnapshot.rectification(), stripBase64Images(generated.rectification())));
  }

  private String stripInlineImages(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    String cleaned = stripBase64Images(value);
    cleaned = INLINE_IMAGE_ROW.matcher(cleaned).replaceAll("");
    cleaned = INLINE_FIGURE.matcher(cleaned).replaceAll("");
    cleaned = INLINE_IMAGE_TAG.matcher(cleaned).replaceAll("");
    return cleaned;
  }

  private String stripBase64Images(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    return INLINE_BASE64_IMAGE.matcher(INLINE_BASE64_FIGURE.matcher(value).replaceAll("")).replaceAll("");
  }

  private ReportSections sanitizeAiInlineImages(ReportSections sections, List<String> allowedImageIds) {
    Set<String> allowed = new LinkedHashSet<>(allowedImageIds == null ? List.of() : allowedImageIds);
    Set<String> seen = new LinkedHashSet<>();
    return new ReportSections(
        sanitizeAiInlineImages(sections.title(), allowed, seen),
        sanitizeAiInlineImages(sections.situation(), allowed, seen),
        sanitizeAiInlineImages(sections.analysis(), allowed, seen),
        sanitizeAiInlineImages(sections.rectification(), allowed, seen));
  }

  private String sanitizeAiInlineImages(String value, Set<String> allowed, Set<String> seen) {
    if (value == null || value.isBlank()) {
      return value;
    }
    String cleaned = INLINE_BASE64_FIGURE.matcher(value).replaceAll("");
    cleaned = INLINE_BASE64_IMAGE.matcher(cleaned).replaceAll("");
    cleaned = removeDuplicateOrUnknownFigures(cleaned, allowed, seen);
    return removeUnknownImageTags(cleaned, allowed);
  }

  private String removeDuplicateOrUnknownFigures(String value, Set<String> allowed, Set<String> seen) {
    Matcher matcher = INLINE_FIGURE.matcher(value);
    StringBuffer buffer = new StringBuffer();
    while (matcher.find()) {
      String fileId = matcher.group(1);
      String replacement = "";
      if (allowed.contains(fileId) && !seen.contains(fileId)) {
        seen.add(fileId);
        replacement = matcher.group();
      }
      matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(buffer);
    return buffer.toString();
  }

  private String removeUnknownImageTags(String value, Set<String> allowed) {
    Matcher matcher = INLINE_IMAGE_TAG.matcher(value);
    StringBuffer buffer = new StringBuffer();
    while (matcher.find()) {
      Matcher idMatcher = INLINE_FILE_REFERENCE.matcher(matcher.group());
      String replacement = "";
      if (idMatcher.find() && allowed.contains(idMatcher.group(1))) {
        replacement = matcher.group();
      }
      matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(buffer);
    return buffer.toString();
  }

  private ReportSections normalizeAiReportText(ReportSections sections) {
    return new ReportSections(
        normalizeAiReportText(sections.title()),
        stripLeadingSectionHeading(normalizeAiReportText(sections.situation()), "一、情况说明"),
        stripLeadingSectionHeading(normalizeAiReportText(sections.analysis()), "二、排查分析"),
        stripLeadingSectionHeading(normalizeAiReportText(sections.rectification()), "三、整改小结"));
  }

  private ReportSections normalizePositionRatedPowerFormula(Draft draft, ReportSections sections) {
    Optional<PositionRatedPowerContext> context =
        positionRatedPowerContext(draft, draft.sections().analysis(), null, null);
    if (context.isEmpty()) {
      return sections;
    }
    return new ReportSections(
        normalizePositionRatedPowerFormula(sections.title(), context.get()),
        normalizePositionRatedPowerFormula(sections.situation(), context.get()),
        normalizePositionRatedPowerFormula(sections.analysis(), context.get()),
        normalizePositionRatedPowerFormula(sections.rectification(), context.get()));
  }

  private String normalizePositionRatedPowerFormula(String value, PositionRatedPowerContext context) {
    if (value == null || value.isBlank() || !ADDITIVE_RATED_POWER_FORMULA.matcher(value).find()) {
      return value;
    }
    if (!context.summer()) {
      return ADDITIVE_RATED_POWER_FORMULA
          .matcher(value)
          .replaceAll(java.util.regex.Matcher.quoteReplacement(context.nonSummerExpressionPrefix()));
    }
    if (context.airPower().isBlank()) {
      return value;
    }
    return ADDITIVE_RATED_POWER_FORMULA
        .matcher(value)
        .replaceAll(java.util.regex.Matcher.quoteReplacement(context.summerExpressionPrefix()));
  }

  private ReportSections normalizeAnalysisReasonPosition(ReportSections sections) {
    return new ReportSections(
        sections.title(),
        sections.situation(),
        moveAnalysisReasonToEnd(sections.analysis()),
        sections.rectification());
  }

  private String moveAnalysisReasonToEnd(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    var blocks = new ArrayList<String>();
    String withoutBlocks = extractMatches(ANALYSIS_REASON_BLOCK, value, blocks);
    String withoutLines = extractMatches(ANALYSIS_REASON_TEXT_LINE, withoutBlocks, blocks);
    if (blocks.isEmpty()) {
      return value;
    }
    String body = withoutLines.stripTrailing();
    String reason = String.join("", blocks).strip();
    if (body.isBlank()) {
      return reason;
    }
    return body + (HTML_ELEMENT.matcher(reason).find() ? "" : "\n") + reason;
  }

  private String extractMatches(Pattern pattern, String value, List<String> matches) {
    var matcher = pattern.matcher(value);
    var result = new StringBuffer();
    while (matcher.find()) {
      matches.add(matcher.group().strip());
      matcher.appendReplacement(result, "");
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private String normalizeAiReportText(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    return value
        .replace("主要系", "主要是")
        .replace("原因系", "原因是")
        .replace("判断系", "判断是")
        .replace("确认系", "确认是")
        .replace("超标系", "超标是")
        .replace("差异系", "差异是")
        .replace("增长系", "增长是")
        .replace("升高系", "升高是")
        .replace("增加系", "增加是")
        .replace("问题系", "问题是")
        .replace("系因为", "是因为")
        .replace("系由于", "是由于")
        .replace("系由", "是由");
  }

  private String stripLeadingSectionHeading(String value, String heading) {
    if (value == null || value.isBlank()) {
      return value;
    }
    String escaped = Pattern.quote(heading);
    String withoutHtmlHeading =
        value.replaceFirst(
            "(?is)^\\s*<(?:h[1-6]|p|div)\\b[^>]*>\\s*"
                + escaped
                + "\\s*[：:]?\\s*</(?:h[1-6]|p|div)>\\s*",
            "");
    return withoutHtmlHeading.replaceFirst(
        "(?is)^\\s*" + escaped + "\\s*[：:]?\\s*(?:<br\\s*/?>|\\R)?\\s*", "");
  }

  private ReportSections normalizeImageCaptionPositions(ReportSections sections) {
    return new ReportSections(
        normalizeImageCaptionPositions(sections.title()),
        normalizeImageCaptionPositions(sections.situation()),
        normalizeImageCaptionPositions(sections.analysis()),
        normalizeImageCaptionPositions(sections.rectification()));
  }

  private String normalizeImageCaptionPositions(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    String normalized = IMAGE_FOLLOWED_BY_LABEL_BLOCK.matcher(value).replaceAll("$2$1");
    return IMAGE_FOLLOWED_BY_LABEL_TEXT.matcher(normalized).replaceAll("$2$1");
  }

  private String preserveInlineFigures(String original, String generated) {
    String merged = generated == null ? "" : generated;
    List<InlineImageUnit> originalUnits = inlineImageUnits(original);
    if (originalUnits.isEmpty()) {
      return merged;
    }
    if (!HTML_ELEMENT.matcher(merged).find()) {
      merged =
          "<div>" + htmlEscape(merged).replace("\r\n", "<br />").replace("\n", "<br />") + "</div>";
    }
    for (InlineImageUnit unit : originalUnits) {
      String restored = replaceFirstInlineImageUnit(merged, unit);
      if (!restored.equals(merged)) {
        merged = restored;
      } else if (!containsAnyInlineImageId(merged, unit.fileIds())) {
        merged += unit.html();
      }
    }
    return merged;
  }

  private List<InlineImageUnit> inlineImageUnits(String html) {
    String source = html == null ? "" : html;
    var units = new ArrayList<InlineImageUnit>();
    int index = 0;
    while (index < source.length()) {
      Matcher rowMatcher = INLINE_IMAGE_ROW.matcher(source);
      Matcher figureMatcher = INLINE_FIGURE.matcher(source);
      boolean hasRow = rowMatcher.find(index);
      boolean hasFigure = figureMatcher.find(index);
      if (!hasRow && !hasFigure) {
        break;
      }
      if (hasRow && (!hasFigure || rowMatcher.start() <= figureMatcher.start())) {
        units.add(new InlineImageUnit(inlineFileIdsInOrder(rowMatcher.group()), rowMatcher.group()));
        index = rowMatcher.end();
      } else {
        units.add(new InlineImageUnit(List.of(figureMatcher.group(1)), figureMatcher.group()));
        index = figureMatcher.end();
      }
    }
    return units.stream().filter((unit) -> !unit.fileIds().isEmpty()).toList();
  }

  private String replaceFirstInlineImageUnit(String html, InlineImageUnit unit) {
    if (unit.fileIds().size() > 1) {
      return replaceInlineImageRowUnit(html, unit);
    }
    Matcher rowMatcher = INLINE_IMAGE_ROW.matcher(html == null ? "" : html);
    while (rowMatcher.find()) {
      if (containsAllInlineImageIds(rowMatcher.group(), unit.fileIds())) {
        return html.substring(0, rowMatcher.start()) + unit.html() + html.substring(rowMatcher.end());
      }
    }
    for (String id : unit.fileIds()) {
      Pattern figurePattern =
          Pattern.compile(
              "(?is)\\s*<figure\\b[^>]*data-file-id=[\"']"
                  + Pattern.quote(id)
                  + "[\"'][^>]*>.*?</figure>\\s*");
      Matcher figureMatcher = figurePattern.matcher(html);
      if (figureMatcher.find()) {
        return figureMatcher.replaceFirst(Matcher.quoteReplacement(unit.html()));
      }
    }
    return html;
  }

  private String replaceInlineImageRowUnit(String html, InlineImageUnit unit) {
    String source = html == null ? "" : html;
    String placeholder = "__INLINE_IMAGE_ROW_RESTORE_" + Math.abs(unit.html().hashCode()) + "__";
    Matcher rowMatcher = INLINE_IMAGE_ROW.matcher(source);
    while (rowMatcher.find()) {
      if (containsAllInlineImageIds(rowMatcher.group(), unit.fileIds())) {
        String withPlaceholder =
            source.substring(0, rowMatcher.start())
                + placeholder
                + source.substring(rowMatcher.end());
        return removeInlineFiguresByIds(withPlaceholder, unit.fileIds()).replace(placeholder, unit.html());
      }
    }
    String withPlaceholder = source;
    boolean inserted = false;
    for (String id : unit.fileIds()) {
      Pattern figurePattern = inlineFigurePattern(id);
      Matcher figureMatcher = figurePattern.matcher(withPlaceholder);
      if (!inserted && figureMatcher.find()) {
        withPlaceholder = figureMatcher.replaceFirst(Matcher.quoteReplacement(placeholder));
        inserted = true;
      }
    }
    if (!inserted) {
      return source;
    }
    return removeInlineFiguresByIds(withPlaceholder, unit.fileIds()).replace(placeholder, unit.html());
  }

  private String removeInlineFiguresByIds(String html, List<String> ids) {
    String cleaned = html == null ? "" : html;
    for (String id : ids) {
      cleaned = inlineFigurePattern(id).matcher(cleaned).replaceAll("");
    }
    return cleaned;
  }

  private Pattern inlineFigurePattern(String id) {
    return Pattern.compile(
        "(?is)\\s*<figure\\b[^>]*data-file-id=[\"']"
            + Pattern.quote(id)
            + "[\"'][^>]*>.*?</figure>\\s*");
  }

  @SuppressWarnings("unused")
  private String restoreInlineImageRows(String original, String generated) {
    String merged = generated == null ? "" : generated;
    var rowMatcher = INLINE_IMAGE_ROW.matcher(original == null ? "" : original);
    int rowIndex = 0;
    while (rowMatcher.find()) {
      String rowHtml = rowMatcher.group();
      List<String> rowIds = inlineFileIdsInOrder(rowHtml);
      if (rowIds.size() < 2 || containsInlineImageRow(merged, rowIds)) {
        rowIndex++;
        continue;
      }
      String placeholder = "__INLINE_IMAGE_ROW_" + rowIndex + "__";
      boolean inserted = false;
      for (String id : rowIds) {
        Pattern figurePattern =
            Pattern.compile(
                "(?is)\\s*<figure\\b[^>]*data-file-id=[\"']"
                    + Pattern.quote(id)
                    + "[\"'][^>]*>.*?</figure>\\s*");
        var figureMatcher = figurePattern.matcher(merged);
        if (!inserted && figureMatcher.find()) {
          merged = figureMatcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(placeholder));
          inserted = true;
        }
        merged = figurePattern.matcher(merged).replaceAll("");
      }
      if (inserted) {
        merged = merged.replace(placeholder, rowHtml);
      } else if (!containsAnyInlineImageId(merged, rowIds)) {
        merged += rowHtml;
      }
      rowIndex++;
    }
    return merged;
  }

  private boolean containsAllInlineImageIds(String html, List<String> ids) {
    for (String id : ids) {
      if (!containsInlineImageId(html, id)) {
        return false;
      }
    }
    return true;
  }

  private boolean containsAnyInlineImageId(String html, List<String> ids) {
    for (String id : ids) {
      if (containsInlineImageId(html, id)) {
        return true;
      }
    }
    return false;
  }

  private boolean containsInlineImageId(String html, String id) {
    return html.contains("data-file-id=\"" + id + "\"") || html.contains("data-file-id='" + id + "'");
  }

  private boolean containsInlineImageRow(String html, List<String> ids) {
    var matcher = INLINE_IMAGE_ROW.matcher(html == null ? "" : html);
    while (matcher.find()) {
      if (containsAllInlineImageIds(matcher.group(), ids)) {
        return true;
      }
    }
    return false;
  }

  private String htmlEscape(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private PreparedStatement insertDraft(
      java.sql.Connection connection,
      String publicId,
      long snapshotId,
      ReportSections sections,
      String actor)
      throws SQLException {
    PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO report_draft
              (public_id, billing_point_snapshot_id, status, title, situation, analysis,
               rectification, current_version_no, created_by, updated_by)
            VALUES (?, ?, 'DRAFT', ?, ?, ?, ?, 0, ?, ?)
            """,
            new String[] {"id"});
    statement.setString(1, publicId);
    statement.setLong(2, snapshotId);
    statement.setString(3, sections.title());
    statement.setString(4, sections.situation());
    statement.setString(5, sections.analysis());
    statement.setString(6, sections.rectification());
    statement.setString(7, actor);
    statement.setString(8, actor);
    return statement;
  }

  private PreparedStatement insertCorrectionDraft(
      java.sql.Connection connection,
      String publicId,
      long snapshotId,
      String reportId,
      ReportSections sections,
      List<String> imageIds,
      String reason,
      String actor)
      throws SQLException {
    PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO report_draft
              (public_id, billing_point_snapshot_id, status, title, situation, analysis,
               rectification, current_version_no, formal_report_public_id, ai_final_reason,
               current_image_file_ids_json, analysis_status, created_by, updated_by)
            VALUES (?, ?, 'CORRECTING', ?, ?, ?, ?, 0, ?, ?, ?, 'PENDING_ANALYSIS', ?, ?)
            """,
            new String[] {"id"});
    statement.setString(1, publicId);
    statement.setLong(2, snapshotId);
    statement.setString(3, sections.title());
    statement.setString(4, sections.situation());
    statement.setString(5, sections.analysis());
    statement.setString(6, sections.rectification());
    statement.setString(7, reportId);
    statement.setString(8, reason);
    statement.setString(9, writeJson(imageIds));
    statement.setString(10, actor);
    statement.setString(11, actor);
    return statement;
  }

  private void updateDraft(
      long draftId,
      ReportSections sections,
      List<String> imageFileIds,
      int version,
      String initialReason,
      String finalReason,
      String actor,
      long expectedEntityVersion) {
    requireSections(sections);
    int updated =
        jdbcTemplate.update(
            """
        UPDATE report_draft
           SET title = ?, situation = ?, analysis = ?, rectification = ?,
               current_image_file_ids_json = ?, current_version_no = ?,
               ai_initial_reason = COALESCE(NULLIF(?, ''), ai_initial_reason),
               ai_final_reason = COALESCE(NULLIF(?, ''), ai_final_reason),
               updated_at = CURRENT_TIMESTAMP(3),
               updated_by = ?, version = version + 1
         WHERE id = ? AND version = ? AND status IN ('DRAFT', 'CORRECTING', 'AI_COMPLETED', 'AI_FAILED')
        """,
            sections.title(),
            sections.situation(),
            sections.analysis(),
            sections.rectification(),
            writeJson(imageFileIds),
            version,
            initialReason,
            finalReason,
            actor,
            draftId,
            expectedEntityVersion);
    if (updated != 1) {
      throw new ResourceConflictException("STALE_DRAFT_VERSION", "工作稿已变化，请刷新后重试");
    }
  }

  private Draft loadDraft(String publicId) {
    return jdbcTemplate
        .query(
            """
            SELECT d.id, d.public_id, d.status, d.title, d.situation, d.analysis,
                   d.rectification, d.current_version_no, d.formal_report_public_id,
                   d.current_image_file_ids_json,
                   d.analysis_status, d.analysis_task_public_id, d.analysis_error_code,
                   d.analysis_submitted_at, d.analysis_completed_at,
                   d.created_at, d.updated_at, d.version,
                   s.public_id AS snapshot_public_id, s.billing_point_code,
                   COALESCE(m.billing_point_name, s.billing_point_name) AS billing_point_name,
                   s.city_code, c.name AS city_name, s.district_name,
                   m.resource_summary_json AS master_json,
                   s.data_period,
                   COALESCE(a.audit_status, 'NOT_APPLICABLE') AS audit_status,
                    a.over_limit_type, a.yoy_result, a.yoy_ratio, a.mom_result, a.mom_ratio,
                    a.rated_result, a.rated_ratio, a.max_ratio
              FROM report_draft d
              JOIN billing_point_snapshot s ON s.id = d.billing_point_snapshot_id
              JOIN city c ON c.code = s.city_code
              LEFT JOIN billing_point_master m
                ON m.city_code = s.city_code
               AND m.billing_point_code = s.billing_point_code
              LEFT JOIN audit_result a
                ON a.billing_point_code = s.billing_point_code AND a.data_period = s.data_period AND a.city_code = s.city_code
             WHERE d.public_id = ?
            """,
            (resultSet, rowNumber) ->
                new Draft(
                    resultSet.getLong("id"),
                    resultSet.getString("public_id"),
                    resultSet.getString("snapshot_public_id"),
                    resultSet.getString("billing_point_code"),
                    resultSet.getString("billing_point_name"),
                    resultSet.getString("city_code"),
                    resultSet.getString("city_name"),
                    firstNonBlank(
                        jsonLikeValue(resultSet.getString("master_json"), "所属区县"),
                        resultSet.getString("district_name")),
                    resultSet.getString("data_period"),
                     resultSet.getString("audit_status"),
                     resultSet.getString("over_limit_type"),
                     overLimitDisplayType(resultSet),
                     resultSet.getBigDecimal("max_ratio"),
                     overLimitRatios(resultSet),
                    resultSet.getString("status"),
                    new ReportSections(
                        resultSet.getString("title"),
                        resultSet.getString("situation"),
                        resultSet.getString("analysis"),
                        resultSet.getString("rectification")),
                    resultSet.getInt("current_version_no"),
                    readStringList(resultSet.getString("current_image_file_ids_json")),
                    resultSet.getString("formal_report_public_id"),
                    resultSet.getString("analysis_status"),
                    resultSet.getString("analysis_task_public_id"),
                    resultSet.getString("analysis_error_code"),
                    resultSet.getObject("analysis_submitted_at", LocalDateTime.class),
                    resultSet.getObject("analysis_completed_at", LocalDateTime.class),
                    loadMessages(resultSet.getLong("id")),
                    resultSet.getObject("created_at", LocalDateTime.class),
                    resultSet.getObject("updated_at", LocalDateTime.class),
                    resultSet.getLong("version")),
            publicId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("报告工作稿"));
  }

  private String overLimitTypeLabel(String value) {
    if (value == null || value.isBlank()) {
      return "未分类";
    }
    return switch (value) {
      case "ONLY_YOY" -> "仅同比超标";
      case "ONLY_MOM" -> "仅环比超标";
      case "ONLY_RATED" -> "仅额定标杆超标";
      case "MULTIPLE" -> "多项超标";
      case "NONE" -> "未超标";
      default -> value;
    };
  }

  private String overLimitDisplayType(java.sql.ResultSet resultSet) throws SQLException {
    String overLimitType = resultSet.getString("over_limit_type");
    if (!"MULTIPLE".equals(overLimitType)) {
      return overLimitTypeLabel(overLimitType);
    }

    var labels = new ArrayList<String>();
    if ("OVER_LIMIT".equals(resultSet.getString("yoy_result"))) {
      labels.add("同比");
    }
    if ("OVER_LIMIT".equals(resultSet.getString("mom_result"))) {
      labels.add("环比");
    }
    if ("OVER_LIMIT".equals(resultSet.getString("rated_result"))) {
      labels.add("额定标杆");
    }

    return labels.isEmpty() ? "超标" : String.join("、", labels) + "超标";
  }

  private List<OverLimitRatio> overLimitRatios(java.sql.ResultSet resultSet) throws SQLException {
    var ratios = new ArrayList<OverLimitRatio>();
    addOverLimitRatio(ratios, resultSet, "yoy_result", "yoy_ratio", "YOY", "同比");
    addOverLimitRatio(ratios, resultSet, "mom_result", "mom_ratio", "MOM", "环比");
    addOverLimitRatio(ratios, resultSet, "rated_result", "rated_ratio", "RATED", "额定标杆");
    return ratios;
  }

  private void addOverLimitRatio(
      List<OverLimitRatio> ratios,
      java.sql.ResultSet resultSet,
      String resultColumn,
      String ratioColumn,
      String type,
      String label)
      throws SQLException {
    if ("OVER_LIMIT".equals(resultSet.getString(resultColumn))) {
      ratios.add(new OverLimitRatio(type, label, resultSet.getBigDecimal(ratioColumn)));
    }
  }

  private Snapshot snapshot(String publicId) {
    return jdbcTemplate
        .query(
            """
            SELECT s.id, s.public_id, s.billing_point_code,
                   COALESCE(m.billing_point_name, s.billing_point_name) AS billing_point_name,
                   s.city_code, s.data_period, COALESCE(a.audit_status, 'NOT_APPLICABLE') audit_status,
                   r.public_id AS report_id
              FROM billing_point_snapshot s
              LEFT JOIN billing_point_master m
                ON m.city_code = s.city_code
               AND m.billing_point_code = s.billing_point_code
              LEFT JOIN audit_result a
                ON a.billing_point_code = s.billing_point_code AND a.data_period = s.data_period AND a.city_code = s.city_code
              LEFT JOIN audit_report r ON r.billing_point_snapshot_id = s.id
             WHERE s.public_id = ?
            """,
            (resultSet, rowNumber) ->
                new Snapshot(
                    resultSet.getLong("id"),
                    resultSet.getString("public_id"),
                    resultSet.getString("billing_point_code"),
                    resultSet.getString("billing_point_name"),
                    resultSet.getString("city_code"),
                    resultSet.getString("data_period"),
                    resultSet.getString("audit_status"),
                    resultSet.getString("report_id")),
            publicId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("报账点账期"));
  }

  private SourceReport sourceReport(String publicId) {
    return jdbcTemplate
        .query(
            """
            SELECT r.public_id, r.title, r.situation, r.analysis, r.rectification,
                   s.id AS snapshot_id, s.city_code
              FROM audit_report r
              JOIN billing_point_snapshot s ON s.id = r.billing_point_snapshot_id
             WHERE r.public_id = ?
            """,
            (rs, row) ->
                new SourceReport(
                    rs.getString("public_id"),
                    rs.getLong("snapshot_id"),
                    rs.getString("city_code"),
                    nonBlank(rs.getString("title"), "电费稽核报告"),
                    nonBlank(rs.getString("situation"), ""),
                    nonBlank(rs.getString("analysis"), ""),
                    nonBlank(rs.getString("rectification"), "")),
            publicId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("正式报告"));
  }

  private Draft findBySnapshotId(long snapshotId) {
    List<String> ids =
        jdbcTemplate.queryForList(
            "SELECT public_id FROM report_draft WHERE billing_point_snapshot_id = ?",
            String.class,
            snapshotId);
    return ids.isEmpty() ? null : loadDraft(ids.getFirst());
  }

  private void ensureEditable(Draft draft) {
    if (!isEditableDraftStatus(draft.status())) {
      throw new ResourceConflictException("DRAFT_NOT_EDITABLE", "工作稿当前状态不可编辑，请刷新任务状态");
    }
  }

  private boolean isEditableDraftStatus(String status) {
    return "DRAFT".equals(status)
        || "CORRECTING".equals(status)
        || "AI_COMPLETED".equals(status)
        || "AI_FAILED".equals(status);
  }

  private void requireExpectedVersion(Draft draft, long expectedVersion) {
    if (draft.entityVersion() != expectedVersion) {
      throw new ResourceConflictException("STALE_DRAFT_VERSION", "工作稿已变化，请刷新后重试");
    }
  }

  private void lockVersion(long draftId, long expectedVersion) {
    int matched =
        jdbcTemplate.update(
            """
            UPDATE report_draft
               SET version = version
             WHERE id = ? AND version = ? AND status IN ('DRAFT', 'CORRECTING', 'AI_COMPLETED', 'AI_FAILED')
            """,
            draftId,
            expectedVersion);
    if (matched != 1) {
      throw new ResourceConflictException("STALE_DRAFT_VERSION", "工作稿已变化，请刷新后重试");
    }
  }

  private String requireInstruction(String instruction) {
    String value = instruction == null ? "" : instruction.trim();
    if (value.isEmpty() || value.length() > 4_000) {
      throw new BusinessRuleException("AI_INSTRUCTION_INVALID", "AI 指令长度必须为 1 到 4000 个字符");
    }
    return value;
  }

  private String classifyIntent(String intent, String instruction) {
    String requested = intent == null ? "AUTO" : intent.trim().toUpperCase(java.util.Locale.ROOT);
    if ("ASK".equals(requested) || "IMAGE_ANALYSIS".equals(requested)) {
      return requested;
    }
    if ("CORRECTION".equals(requested)
        || ("AUTO".equals(requested)
            && java.util.regex.Pattern.compile("(错了|不对|不是|并非|实际是|其实是|真实原因|正确原因|原因应为|应为|已完成)")
                .matcher(instruction)
                .find())) {
      return "CORRECTION";
    }
    if ("EDIT".equals(requested)
        || ("AUTO".equals(requested)
            && java.util.regex.Pattern.compile("(修改|补充|优化|重写|改成|替换|添加|增加|写入|删除|移除|调整|更新|润色|完善|追加)")
                .matcher(instruction)
                .find())) {
      return "EDIT";
    }
    if ("AUTO".equals(requested)) {
      return "ASK";
    }
    throw new BusinessRuleException("AI_INTENT_INVALID", "不支持该 AI 助手意图");
  }

  private void requireSections(ReportSections sections) {
    if (sections == null
        || isBlank(sections.title())
        || isBlank(sections.situation())) {
      throw new BusinessRuleException("REPORT_SECTIONS_REQUIRED", "报告标题和情况说明不能为空");
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private boolean isBoundDraftImage(long draftId, String imageFileId) {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM report_draft_image i
              JOIN stored_file f ON f.id = i.file_id
             WHERE i.draft_id = ? AND f.public_id = ?
            """,
            Integer.class,
            draftId,
            imageFileId);
    return count != null && count > 0;
  }

  private void requireScope(CurrentUser actor, String cityCode) {
    if (!actor.roles().contains(Role.SUPER_ADMIN) && !actor.cityCode().equals(cityCode)) {
      throw new AccessDeniedException("Report draft is outside city scope");
    }
  }

  private void requireFileOwner(CurrentUser actor, String owner) {
    if (!actor.roles().contains(Role.SUPER_ADMIN) && !actor.username().equals(owner)) {
      throw new AccessDeniedException("Draft image is outside user scope");
    }
  }

  private void requireFileOwner(String actor, String owner) {
    if (actor == null || !actor.equals(owner)) {
      throw new AccessDeniedException("Draft image is outside user scope");
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Draft state could not be serialized", exception);
    }
  }

  private List<String> readStringList(String json) {
    String payload = json == null || json.isBlank() ? "[]" : json;
    try {
      return objectMapper.readValue(payload, STRING_LIST);
    } catch (JacksonException exception) {
      // H2 exposes a JSON value inserted through JDBC as a quoted JSON string, while MySQL
      // exposes the array directly. Accept both representations so version recovery behaves the
      // same in tests and production.
      try {
        String unwrapped = objectMapper.readValue(payload, String.class);
        return objectMapper.readValue(unwrapped, STRING_LIST);
      } catch (JacksonException nestedException) {
        throw new IllegalStateException("Persisted draft images are invalid JSON", exception);
      }
    }
  }

  private List<DraftMessage> loadMessages(long draftId) {
    return jdbcTemplate.query(
        """
        SELECT public_id, intent, user_content, assistant_content, changed_draft,
               image_file_ids_json, created_at
          FROM report_draft_message
         WHERE draft_id = ?
         ORDER BY created_at, id
        """,
        (rs, row) ->
            new DraftMessage(
                rs.getString("public_id"),
                rs.getString("intent"),
                rs.getString("user_content"),
                rs.getString("assistant_content"),
                rs.getBoolean("changed_draft"),
                readStringList(rs.getString("image_file_ids_json")),
                rs.getObject("created_at", LocalDateTime.class)),
        draftId);
  }

  private String compact(String value, int maxLength) {
    if (value == null || value.isBlank()) {
      return "";
    }
    String normalized = value.replaceAll("(?is)data:image/[^;]+;base64,[A-Za-z0-9+/=]+", "[历史图片]");
    return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
  }

  private String historicalReasonSummary(String value) {
    String normalized = normalizeHistoricalText(value);
    if (normalized.isBlank()) {
      return "历史报告未提取到明确原因";
    }
    var sentences = new ArrayList<String>();
    for (String sentence : normalized.split("[。；;\\n]+")) {
      String trimmed = sentence.trim();
      if (trimmed.length() < 8) {
        continue;
      }
      if (containsAny(
          trimmed,
          "原因",
          "因为",
          "导致",
          "引起",
          "分摊比例",
          "下电",
          "搬迁",
          "新增",
          "资管",
          "额定功率",
          "电表",
          "空调",
          "业务量")) {
        sentences.add(trimmed);
      }
    }
    return sentences.stream()
        .map(sentence -> compact(sentence, 360))
        .distinct()
        .limit(4)
        .reduce((left, right) -> left + "；" + right)
        .orElse("历史报告未提取到明确原因");
  }

  private String historicalReasonTypes(String value) {
    String text = normalizeHistoricalText(value);
    var types = new ArrayList<String>();
    if (containsAny(text, "资管", "台账", "额定功率", "标杆", "功率未及时", "无功率")) {
      types.add("资管系统/台账/额定功率未及时更新");
    }
    if (containsAny(text, "分摊", "下电", "退出分摊", "电信设备", "分摊比例")) {
      types.add("分摊比例变化/电信下电/退出分摊");
    }
    if (containsAny(text, "新增", "搬迁", "扩容", "设备增加", "新增设备", "投运", "负载")) {
      types.add("设备新增/搬迁/扩容");
    }
    if (containsAny(text, "空调", "高温", "夏季", "温度", "制冷")) {
      types.add("空调/高温/季节运行");
    }
    if (containsAny(text, "电表", "倍率", "计量", "合表", "合并用一个电表", "共用1个电表", "直供电")) {
      types.add("电表/倍率/合表/计量变化");
    }
    if (containsAny(text, "业务量", "用电量波动", "业务波动")) {
      types.add("业务量波动");
    }
    return types.isEmpty() ? "未分类" : String.join("、", types);
  }

  private String historicalEquipmentStyleExamples(String value) {
    String normalized =
        value(value)
            .replaceAll("(?is)<br\\s*/?>", "。")
            .replaceAll("(?is)</(?:p|div|h[1-6]|li)>", "。")
            .replaceAll("(?is)<[^>]+>", " ")
            .replace("&nbsp;", " ")
            .replace("&#x20;", " ")
            .replaceAll("\\s+", " ")
            .trim();
    if (normalized.isBlank()) {
      return "";
    }
    var examples = new ArrayList<String>();
    for (String sentence : normalized.split("[。；;\\n]+")) {
      String trimmed = sentence.trim();
      if (trimmed.length() < 6) {
        continue;
      }
      boolean looksLikeEquipment =
          containsAny(
              trimmed,
              "设备情况",
              "BBU",
              "RRU",
              "AAU",
              "700M",
              "NB",
              "诺基亚",
              "华为",
              "中兴",
              "爱立信");
      boolean hasOperatorOrCaption =
          containsAny(trimmed, "移动", "联通", "电信", "设备情况", "设备机柜", "机房全景");
      if (looksLikeEquipment && hasOperatorOrCaption) {
        examples.add(compact(trimmed, 360));
      }
    }
    return examples.stream()
        .distinct()
        .limit(4)
        .reduce((left, right) -> left + "；" + right)
        .orElse("");
  }

  private String normalizeHistoricalText(String value) {
    return value(value)
        .replaceAll("(?is)<[^>]+>", " ")
        .replace("&nbsp;", " ")
        .replace("&#x20;", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private boolean containsAny(String text, String... candidates) {
    String safeText = value(text);
    for (String candidate : candidates) {
      if (safeText.contains(candidate)) {
        return true;
      }
    }
    return false;
  }

  private String value(Object value) {
    return value == null ? "" : value.toString();
  }

  private String decimalText(Object value) {
    if (value instanceof BigDecimal decimal) {
      return decimal.stripTrailingZeros().toPlainString();
    }
    return value(value);
  }

  private String jsonLikeValue(String source, String key) {
    if (source == null || source.isBlank() || key == null || key.isBlank()) {
      return "";
    }
    try {
      Map<String, Object> values = objectMapper.readValue(source, new TypeReference<>() {});
      Object value = values.get(key);
      if (value != null && !value.toString().isBlank()) {
        return value.toString().trim();
      }
    } catch (JacksonException ignored) {
      // Fall back to loose text extraction because some snapshots store raw text fragments.
    }
    String normalized = source.replace("\\\"", "\"");
    if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
      normalized = normalized.substring(1, normalized.length() - 1);
    }
    String quotedKey = Pattern.quote(key);
    var matcher =
        Pattern.compile(
                "(?is)[\"']?"
                    + quotedKey
                    + "[\"']?\\s*[:：]\\s*[\"']?([^,\"'，}\\]\\s]+)")
            .matcher(normalized);
    return matcher.find() ? matcher.group(1).trim() : "";
  }

  private String firstNonBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private record PositionRatedPowerContext(
      boolean summer,
      int days,
      String mainPower,
      String airPower,
      String towerAirPower,
      String ratedTotal) {

    private String factText() {
      String formula =
          summer
              ? "夏季5月-10月，位置点额定功率=主设备功率+空调总功率"
              : "非夏季1月-4月、11月-12月，位置点额定公式默认只取主设备功率";
      String expression =
          summer && !airPower().isBlank()
              ? "（"
                  + valueOrPending(mainPower(), "主设备功率待核实")
                  + "KW+"
                  + airPower()
                  + "KW）*24小时*"
                  + days()
                  + "天"
              : "（"
                  + valueOrPending(mainPower(), "主设备功率待核实")
                  + "KW）*24小时*"
                  + days()
                  + "天";
      String total = ratedTotal().isBlank() ? "系统已计算额定标杆总量" : ratedTotal() + "度";
      return formula
          + "；主设备功率="
          + valueOrPending(mainPower(), "主设备功率待核实")
          + "KW；空调总功率="
          + valueOrPending(airPower(), "空调总功率未提供")
          + "KW；铁塔空调总额定功率="
          + valueOrPending(towerAirPower(), "铁塔空调总额定功率未提供")
          + "KW；本账期天数="
          + days()
          + "天；报告公式应写为：三费系统中对应额定功率标杆应为"
          + expression
          + "="
          + total
          + "。非夏季如果图片、历史报告或现场材料支持空调运行原因，可以先写“可能与空调运行有关，需人工确认”，但公式仍不得自动加入空调功率；禁止把铁塔空调总额定功率和空调总功率重复叠加。";
    }

    private String nonSummerExpressionPrefix() {
      return "（" + valueOrPending(mainPower(), "主设备功率待核实") + "KW）*24小时*" + days() + "天";
    }

    private String summerExpressionPrefix() {
      return "（"
          + valueOrPending(mainPower(), "主设备功率待核实")
          + "KW+"
          + valueOrPending(airPower(), "空调总功率未提供")
          + "KW）*24小时*"
          + days()
          + "天";
    }

    private static String valueOrPending(String value, String pending) {
      return value == null || value.isBlank() || "-".equals(value) ? pending : value;
    }
  }

  private String nonBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private CorrectionContent prepareCorrectionContent(SourceReport source, String actor) {
    String originalHtml = source.html();

    if (originalHtml == null || originalHtml.isBlank()) {
      return new CorrectionContent(reportSections(source, originalHtml), List.of());
    }

    var generatedImageIds = new java.util.ArrayList<String>();

    String rewrittenHtml = rewriteBase64Figures(originalHtml, actor, generatedImageIds);

    rewrittenHtml = rewriteStandaloneBase64Images(rewrittenHtml, actor, generatedImageIds);

    rewrittenHtml = cloneExistingInlineImages(rewrittenHtml, actor, generatedImageIds);

    if (BASE64_IMAGE_MARKER.matcher(rewrittenHtml).find()) {
      throw new BusinessRuleException(
          "CORRECTION_IMAGE_CONVERSION_FAILED", "原报告中仍存在未转换的内嵌图片，已停止创建更正工作稿");
    }

    List<String> orderedImageIds = inlineFileIdsInOrder(rewrittenHtml);

    ReportSections sections = reportSections(source, rewrittenHtml);

    return new CorrectionContent(sections, orderedImageIds);
  }

  private String rewriteBase64Figures(
      String html, String actor, java.util.List<String> generatedImageIds) {

    var matcher = INLINE_BASE64_FIGURE.matcher(html);
    var output = new StringBuffer();

    while (matcher.find()) {
      String fileId =
          storeCorrectionInlineImage(
              matcher.group(1), matcher.group(2), actor, generatedImageIds.size() + 1);

      generatedImageIds.add(fileId);

      matcher.appendReplacement(
          output, java.util.regex.Matcher.quoteReplacement(inlineImageHtml(fileId)));
    }

    matcher.appendTail(output);
    return output.toString();
  }

  private String rewriteStandaloneBase64Images(
      String html, String actor, java.util.List<String> generatedImageIds) {

    var matcher = INLINE_BASE64_IMAGE.matcher(html);
    var output = new StringBuffer();

    while (matcher.find()) {
      String fileId =
          storeCorrectionInlineImage(
              matcher.group(1), matcher.group(2), actor, generatedImageIds.size() + 1);

      generatedImageIds.add(fileId);

      matcher.appendReplacement(
          output, java.util.regex.Matcher.quoteReplacement(inlineImageHtml(fileId)));
    }

    matcher.appendTail(output);
    return output.toString();
  }

  private String cloneExistingInlineImages(
      String html, String actor, java.util.List<String> generatedImageIds) {

    var existingIds = new java.util.LinkedHashSet<String>();
    var matcher = INLINE_FILE_REFERENCE.matcher(html);

    while (matcher.find()) {
      existingIds.add(matcher.group(1));
    }

    String rewritten = html;

    for (String existingId : existingIds) {
      if (generatedImageIds.contains(existingId)) {
        continue;
      }

      var sourceFile = storedFileService.find(existingId);

      if (!("image/png".equals(sourceFile.mediaType())
          || "image/jpeg".equals(sourceFile.mediaType()))) {
        throw new BusinessRuleException(
            "CORRECTION_IMAGE_TYPE_INVALID", "原报告中存在非 PNG/JPEG 图片，暂无法进入更正工作稿");
      }

      String extension = "image/png".equals(sourceFile.mediaType()) ? "png" : "jpg";

      var copied =
          storedFileService.storeGenerated(
              storedFileService.readBytes(sourceFile),
              "更正报告图片-" + (generatedImageIds.size() + 1) + "." + extension,
              sourceFile.mediaType(),
              "DRAFT_IMAGE",
              actor);

      String newId = copied.publicId();
      rewritten = rewritten.replace(existingId, newId);
      generatedImageIds.add(newId);
    }

    return rewritten;
  }

  private String storeCorrectionInlineImage(
      String imageType, String base64, String actor, int imageNumber) {

    String extension = "png".equalsIgnoreCase(imageType) ? "png" : "jpg";
    String mediaType = "png".equals(extension) ? "image/png" : "image/jpeg";

    byte[] bytes;
    try {
      bytes = Base64.getMimeDecoder().decode(base64);
    } catch (IllegalArgumentException exception) {
      throw new BusinessRuleException("CORRECTION_IMAGE_INVALID", "原报告中存在无法读取的内嵌图片");
    }

    if (bytes.length == 0) {
      throw new BusinessRuleException("CORRECTION_IMAGE_INVALID", "原报告中存在空图片");
    }

    var stored =
        storedFileService.storeGenerated(
            bytes, "更正报告图片-" + imageNumber + "." + extension, mediaType, "DRAFT_IMAGE", actor);

    return stored.publicId();
  }

  private List<String> inlineFileIdsInOrder(String html) {
    if (html == null || html.isBlank()) {
      return List.of();
    }

    var orderedIds = new java.util.LinkedHashSet<String>();
    var matcher = INLINE_FILE_REFERENCE.matcher(html);

    while (matcher.find()) {
      orderedIds.add(matcher.group(1));
    }

    return List.copyOf(orderedIds);
  }

  private String inlineImageHtml(String fileId) {
    String safeId = htmlEscape(fileId);

    return """
        <figure class="inline-report-image" data-file-id="%s">
          <img src="/api/v1/files/%s?inline=true"
               data-file-id="%s"
               alt="稽核证据图片" />
        </figure>
        """
        .formatted(safeId, safeId, safeId)
        .trim();
  }

  private ReportSections reportSections(SourceReport source, String html) {
    if (looksLikeHtml(html)) {
      String fullHtml = fullCorrectionHtml(source, html);
      return new ReportSections(
          cleanReportText(firstMatch(fullHtml, "(?is)<h1[^>]*>(.*?)</h1>", source.title())),
          fullHtml,
          "",
          "");
    }

    String title = cleanReportText(source.title());
    String situation = cleanReportText(source.situation());
    String analysis = cleanReportText(source.analysis());
    String rectification = cleanReportText(source.rectification());

    return new ReportSections(
        nonBlank(title, source.title()),
        nonBlank(situation, "原报告正文待补充。"),
        nonBlank(analysis, "请在此补充更正后的排查分析。"),
        nonBlank(rectification, "请在此补充更正后的整改建议。"));
  }

  private String fullCorrectionHtml(SourceReport source, String situationHtml) {
    var html = new StringBuilder("<article class=\"correction-report-source\">");
    if (!java.util.regex.Pattern.compile("(?is)<h1\\b").matcher(situationHtml).find()) {
      html.append("<h1>").append(htmlEscape(nonBlank(source.title(), "电费稽核报告"))).append("</h1>");
    }
    if (!isHistoricalPlaceholderSections(source) && !hasSectionHeading(situationHtml, "一、情况说明")) {
      html.append("<h2>一、情况说明</h2>");
    }
    html.append(situationHtml);
    if (!isHistoricalPlaceholderSections(source)) {
      appendCorrectionSection(html, "二、排查分析", source.analysis());
      appendCorrectionSection(html, "三、整改小结", source.rectification());
    }
    html.append("</article>");
    return html.toString();
  }

  private boolean isHistoricalPlaceholderSections(SourceReport source) {
    return "历史报告原文转换预览".equals(cleanReportText(source.analysis()))
        && "以原 Word 最终内容为准".equals(cleanReportText(source.rectification()));
  }

  private boolean hasSectionHeading(String html, String heading) {
    if (html == null || html.isBlank()) {
      return false;
    }
    String text = cleanReportText(html);
    return text.startsWith(heading) || text.contains("\n" + heading);
  }

  private void appendCorrectionSection(StringBuilder html, String heading, String content) {
    if (content == null || content.isBlank()) {
      return;
    }
    html.append("<h2>").append(htmlEscape(heading)).append("</h2>");
    if (looksLikeHtml(content)) {
      html.append(content);
      return;
    }
    for (String paragraph : content.split("\\R{2,}|\\R")) {
      if (paragraph.isBlank()) {
        continue;
      }
      html.append("<p>").append(htmlEscape(paragraph.trim())).append("</p>");
    }
  }

  private boolean looksLikeHtml(String value) {
    return value != null
        && Pattern.compile(
                "(?is)</?(div|p|table|tr|td|th|figure|img|section|article|h[1-6]|ul|ol|li)\\b")
            .matcher(value)
            .find();
  }

  private String firstMatch(String value, String pattern, String fallback) {
    var matcher = Pattern.compile(pattern).matcher(value == null ? "" : value);
    return matcher.find() ? matcher.group(1) : fallback;
  }

  private String cleanReportText(String value) {
    return value
        .replaceAll("(?i)<br\\s*/?>", "\n")
        .replaceAll("(?i)</(p|div|section|article|h[1-6]|li|tr)>", "\n")
        .replaceAll("<[^>]+>", "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .replaceAll("(?m)^\\s+", "")
        .replaceAll("\\n{3,}", "\n\n")
        .trim();
  }

  public record Draft(
      long id,
      String publicId,
      String billingPointPeriodId,
      String billingPointCode,
      String billingPointName,
      String cityCode,
      String cityName,
      String district,
      String period,
      String auditStatus,
      String overLimitType,
      String overLimitDisplayType,
      java.math.BigDecimal maxExceedRatio,
      List<OverLimitRatio> overLimitRatios,
      String status,
      ReportSections sections,
      int currentVersion,
      List<String> currentImageFileIds,
      String formalReportId,
      String analysisStatus,
      String analysisTaskId,
      String analysisErrorCode,
      LocalDateTime analysisSubmittedAt,
      LocalDateTime analysisCompletedAt,
      List<DraftMessage> messages,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      long entityVersion) {}

  public record OverLimitRatio(String type, String label, java.math.BigDecimal ratio) {}

  public record DraftMessage(
      String id,
      String intent,
      String userContent,
      String assistantContent,
      boolean changedDraft,
      List<String> imageFileIds,
      LocalDateTime createdAt) {}

  public record DraftVersion(
      String id,
      int version,
      String changeType,
      ReportSections sections,
      List<String> imageFileIds,
      LocalDateTime createdAt,
      String createdBy) {}

  public record DraftImageAccess(StoredFile file, InputStreamResource resource) {}

  public record UploadedImage(String fileId, long entityVersion) {}

  private record AnalysisImageInput(AiImage image, List<String> fileIds) {}

  private record InlineImageUnit(List<String> fileIds, String html) {}

  private record CorrectionContent(ReportSections sections, List<String> imageIds) {}

  public record ImageAnalysisTaskPayload(
      String draftId,
      Integer contentVersion,
      String instruction,
      List<String> imageFileIds,
      ReportSections layoutSections) {}

  private record Snapshot(
      long id,
      String publicId,
      String billingPointCode,
      String billingPointName,
      String cityCode,
      String period,
      String auditStatus,
      String reportId) {}

  private record SourceReport(
      String publicId,
      long snapshotId,
      String cityCode,
      String title,
      String html,
      String analysis,
      String rectification) {
    String situation() {
      return html;
    }
  }
}
