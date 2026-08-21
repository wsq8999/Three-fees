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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
  private static final Pattern INLINE_FILE_REFERENCE =
      Pattern.compile("(?is)data-file-id=[\"']([^\"']+)[\"']");
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
    if (requestedImageIds.size() > 10
        || requestedImageIds.stream().distinct().count() != requestedImageIds.size()) {
      throw new BusinessRuleException("AI_IMAGES_INVALID", "图片数量超过限制或存在重复图片");
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
    if (reportImageIds.size() > 10) {
      throw new BusinessRuleException("AI_IMAGES_TOO_MANY", "当前报告最多支持 10 张分析图片");
    }
    long totalImageBytes = 0;
    var images = new java.util.ArrayList<AiImage>();
    List<String> modelImageIds =
        "IMAGE_ANALYSIS".equals(normalizedIntent) ? reportImageIds : List.of();
    for (String imageId : modelImageIds) {
      var file = storedFileService.find(imageId);
      requireFileOwner(actor, file.createdBy());
      if (!("image/png".equals(file.mediaType()) || "image/jpeg".equals(file.mediaType()))) {
        throw new BusinessRuleException("AI_IMAGE_TYPE_INVALID", "图片仅支持 PNG/JPEG");
      }
      if (file.byteSize() > 10L * 1024 * 1024) {
        throw new BusinessRuleException("AI_IMAGE_TOO_LARGE", "单张分析图片不能超过 10 MiB");
      }
      totalImageBytes += file.byteSize();
      if (totalImageBytes > 20L * 1024 * 1024) {
        throw new BusinessRuleException("AI_IMAGES_TOO_LARGE", "分析图片总大小不能超过 20 MiB");
      }
      images.add(
          new AiImage(file.originalName(), file.mediaType(), storedFileService.readBytes(file)));
    }
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
      bindImages(draft.id(), reportImageIds, result.imageAnalyses(), actor.username());
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
            images.size(),
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
    if (requestedImageIds.size() > 10
        || requestedImageIds.stream().distinct().count() != requestedImageIds.size()) {
      throw new BusinessRuleException("AI_IMAGES_INVALID", "图片数量超过限制或存在重复图片");
    }
    List<String> reportImageIds =
        Stream.concat(draft.currentImageFileIds().stream(), requestedImageIds.stream())
            .distinct()
            .toList();
    validateAnalysisImages(reportImageIds, actor.username());
    String businessKey = imageAnalysisBusinessKey(draft);
    String payloadJson = writeJson(new ImageAnalysisTaskPayload(publicId, safeInstruction, reportImageIds));
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
    return "AI_IMAGE_ANALYSIS:SNAPSHOT:" + draft.billingPointPeriodId();
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
             WHERE id = ? AND version = ? AND status IN ('DRAFT', 'CORRECTING')
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

  @Transactional
  public Draft edit(
      String publicId, ReportSections sections, long expectedVersion, CurrentUser actor) {
    Draft draft = find(publicId, actor);
    ensureEditable(draft);
    requireSections(sections);
    if (draft.entityVersion() != expectedVersion) {
      throw new ResourceConflictException("STALE_DRAFT_VERSION", "工作稿已变化，请刷新后重试");
    }
    int nextVersion = draft.currentVersion() + 1;
    int updated =
        jdbcTemplate.update(
            """
            UPDATE report_draft
               SET title = ?, situation = ?, analysis = ?, rectification = ?,
                   current_version_no = ?, updated_at = CURRENT_TIMESTAMP(3),
                   updated_by = ?, version = version + 1
             WHERE id = ? AND version = ?
            """,
            sections.title(),
            sections.situation(),
            sections.analysis(),
            sections.rectification(),
            nextVersion,
            actor.username(),
            draft.id(),
            expectedVersion);
    if (updated != 1) {
      throw new ResourceConflictException("STALE_DRAFT_VERSION", "工作稿已变化，请刷新后重试");
    }
    saveVersion(
        draft.id(), nextVersion, "MANUAL", sections, draft.currentImageFileIds(), actor.username());
    return find(publicId, actor);
  }

  @Transactional
  public void completeImageAnalysisTask(
      String publicId, String instruction, List<String> imageFileIds, String traceId, String actor) {
    Draft draft = loadDraft(publicId);
    if (!"AI_ANALYZING".equals(draft.analysisStatus())) {
      return;
    }
    List<String> reportImageIds = imageFileIds == null ? draft.currentImageFileIds() : imageFileIds;
    try {
      validateAnalysisImages(reportImageIds, actor);
      var images = new java.util.ArrayList<AiImage>();
      for (String imageId : reportImageIds) {
        var file = storedFileService.find(imageId);
        images.add(
            new AiImage(file.originalName(), file.mediaType(), storedFileService.readBytes(file)));
      }
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
      updated = preserveInlineFigures(draft.sections(), updated);
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
      bindImages(draft.id(), reportImageIds, result.imageAnalyses(), actor);
      saveVersion(draft.id(), version, "IMAGE_ANALYSIS", updated, reportImageIds, actor);
      saveMessage(
          draft,
          "IMAGE_ANALYSIS",
          instruction,
          result.answer(),
          true,
          reportImageIds,
          result.initialReason(),
          result.finalReason(),
          images.size(),
          context,
          actor);
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
    if (draft.currentImageFileIds().size() >= 10) {
      throw new BusinessRuleException("AI_IMAGES_TOO_MANY", "一份报告最多包含 10 张图片");
    }
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
               WHERE id=? AND version=? AND status IN ('DRAFT','CORRECTING')
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
             WHERE id=? AND version=? AND status IN ('DRAFT','CORRECTING')
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
             WHERE id=? AND version=? AND status IN ('DRAFT','CORRECTING')
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
      return existing.orElseThrow();
    }
    ensureEditable(draft);
    requireExpectedVersion(draft, expectedVersion);
    int transitioned =
        jdbcTemplate.update(
            """
        UPDATE report_draft SET status = 'GENERATING', confirmed_at = CURRENT_TIMESTAMP(3),
               confirmed_by = ?, updated_at = CURRENT_TIMESTAMP(3),
               updated_by = ?, version = version + 1
         WHERE id = ? AND version = ? AND status IN ('DRAFT', 'CORRECTING')
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
    if (imageIds.size() > 10 || imageIds.stream().distinct().count() != imageIds.size()) {
      throw new BusinessRuleException("AI_IMAGES_INVALID", "图片数量超过限制或存在重复图片");
    }
    long totalImageBytes = 0;
    for (String imageId : imageIds) {
      var file = storedFileService.find(imageId);
      requireFileOwner(actor, file.createdBy());
      if (!("image/png".equals(file.mediaType()) || "image/jpeg".equals(file.mediaType()))) {
        throw new BusinessRuleException("AI_IMAGE_TYPE_INVALID", "图片仅支持 PNG/JPEG");
      }
      if (file.byteSize() > 10L * 1024 * 1024) {
        throw new BusinessRuleException("AI_IMAGE_TOO_LARGE", "单张分析图片不能超过 10 MiB");
      }
      totalImageBytes += file.byteSize();
      if (totalImageBytes > 20L * 1024 * 1024) {
        throw new BusinessRuleException("AI_IMAGES_TOO_LARGE", "分析图片总大小不能超过 20 MiB");
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
                   s.data_json, a.detail_json
              FROM billing_point_snapshot s
              JOIN city c ON c.code = s.city_code
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
              values.add(new Fact("所属区县", value(rs.getString("district_name"))));
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
              values.add(new Fact("额定标杆结果", value(rs.getString("rated_result"))));
              values.add(new Fact("额定标杆电量", value(rs.getBigDecimal("rated_energy"))));
              values.add(new Fact("额定标杆超标比例", value(rs.getBigDecimal("rated_ratio"))));
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
               hc.image_analysis_text
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
        (rs, row) ->
            new Reference(
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
                            + "；标题="
                            + value(rs.getString("title"))
                            + "；情况="
                            + value(rs.getString("situation"))
                            + "；分析="
                            + value(rs.getString("analysis"))
                            + "；整改="
                            + value(rs.getString("rectification")),
                        6000)
                    + compact(
                        "；历史图片数="
                            + rs.getInt("image_count")
                            + "；历史图片分析状态="
                            + value(rs.getString("image_analysis_status"))
                            + "；历史图片证据="
                            + value(rs.getString("image_analysis_text")),
                        6000),
                rs.getString("city_code")),
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
                "modelImageCount", modelImageCount)));
    return messageId;
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

  private String preserveInlineFigures(String original, String generated) {
    String merged = generated == null ? "" : generated;
    var matcher = INLINE_FIGURE.matcher(original == null ? "" : original);
    var figures = new java.util.ArrayList<String>();
    while (matcher.find()) {
      String fileId = matcher.group(1);
      if (!merged.contains("data-file-id=\"" + fileId + "\"")
          && !merged.contains("data-file-id='" + fileId + "'")) {
        figures.add(matcher.group());
      }
    }
    if (figures.isEmpty()) return merged;
    if (!HTML_ELEMENT.matcher(merged).find()) {
      merged =
          "<div>" + htmlEscape(merged).replace("\r\n", "<br />").replace("\n", "<br />") + "</div>";
    }
    return merged + String.join("", figures);
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
               current_image_file_ids_json, created_by, updated_by)
            VALUES (?, ?, 'CORRECTING', ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)
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
         WHERE id = ? AND version = ? AND status IN ('DRAFT', 'CORRECTING')
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
                   s.billing_point_name, s.city_code, c.name AS city_name, s.district_name,
                   s.data_period,
                   COALESCE(a.audit_status, 'NOT_APPLICABLE') AS audit_status,
                    a.over_limit_type, a.yoy_result, a.yoy_ratio, a.mom_result, a.mom_ratio,
                    a.rated_result, a.rated_ratio, a.max_ratio
              FROM report_draft d
              JOIN billing_point_snapshot s ON s.id = d.billing_point_snapshot_id
              JOIN city c ON c.code = s.city_code
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
                    resultSet.getString("district_name"),
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
            SELECT s.id, s.public_id, s.billing_point_code, s.billing_point_name,
                   s.city_code, s.data_period, COALESCE(a.audit_status, 'NOT_APPLICABLE') audit_status,
                   r.public_id AS report_id
              FROM billing_point_snapshot s
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
    if (!("DRAFT".equals(draft.status()) || "CORRECTING".equals(draft.status()))) {
      throw new ResourceConflictException("DRAFT_NOT_EDITABLE", "工作稿当前状态不可编辑，请刷新任务状态");
    }
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
             WHERE id = ? AND version = ? AND status IN ('DRAFT', 'CORRECTING')
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

  private String value(Object value) {
    return value == null ? "" : value.toString();
  }

  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
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

    if (orderedImageIds.size() > 10) {
      throw new BusinessRuleException("AI_IMAGES_TOO_MANY", "原报告图片超过 10 张，暂无法进入更正工作稿");
    }

    ReportSections sections = reportSections(source, rewrittenHtml);

    return new CorrectionContent(sections, orderedImageIds);
  }

  private String rewriteBase64Figures(
      String html, String actor, java.util.List<String> generatedImageIds) {

    var matcher = INLINE_BASE64_FIGURE.matcher(html);
    var output = new StringBuffer();

    while (matcher.find()) {
      ensureCorrectionImageCapacity(generatedImageIds);

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
      ensureCorrectionImageCapacity(generatedImageIds);

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

      ensureCorrectionImageCapacity(generatedImageIds);

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

  private void ensureCorrectionImageCapacity(java.util.List<String> generatedImageIds) {
    if (generatedImageIds.size() >= 10) {
      throw new BusinessRuleException("AI_IMAGES_TOO_MANY", "一份报告最多包含 10 张图片");
    }
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
      return new ReportSections(
          cleanReportText(firstMatch(html, "(?is)<h1[^>]*>(.*?)</h1>", source.title())),
          html,
          nonBlank(cleanReportText(source.analysis()), "请在此补充更正后的排查分析。"),
          nonBlank(cleanReportText(source.rectification()), "请在此补充更正后的整改建议。"));
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

  private record CorrectionContent(ReportSections sections, List<String> imageIds) {}

  public record ImageAnalysisTaskPayload(
      String draftId, String instruction, List<String> imageFileIds) {}

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
