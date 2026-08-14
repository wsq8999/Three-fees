package com.threefees.report.application;

import com.threefees.ai.application.AiServiceClient;
import com.threefees.ai.application.AiServiceClient.AiImage;
import com.threefees.ai.application.AiServiceClient.Fact;
import com.threefees.ai.application.AiServiceClient.ReportSections;
import com.threefees.ai.application.AiServiceException;
import com.threefees.file.application.StoredFileService;
import com.threefees.identity.application.BusinessRuleException;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.application.ResourceConflictException;
import com.threefees.identity.application.ResourceNotFoundException;
import com.threefees.identity.domain.Role;
import com.threefees.task.application.BusinessTaskRepository;
import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskType;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final AiServiceClient aiServiceClient;
  private final StoredFileService storedFileService;
  private final BusinessTaskRepository taskRepository;

  public ReportDraftService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      AiServiceClient aiServiceClient,
      StoredFileService storedFileService,
      BusinessTaskRepository taskRepository) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.aiServiceClient = aiServiceClient;
    this.storedFileService = storedFileService;
    this.taskRepository = taskRepository;
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
            snapshot.billingPointName() + "物业电费稽核报告",
            "账期：" + snapshot.period() + "；报账点：" + snapshot.billingPointCode() + "。",
            "系统稽核结果为超标，需结合业务凭证和现场证据进一步排查。",
            "请复核计量、合同、缴费依据并形成闭环整改记录。");
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
    return find(publicId, actor);
  }

  @Transactional(readOnly = true)
  public Draft find(String publicId, CurrentUser actor) {
    Draft draft = loadDraft(publicId);
    requireScope(actor, draft.cityCode());
    return draft;
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
    List<String> safeImageIds = imageFileIds == null ? List.of() : List.copyOf(imageFileIds);
    if (!"IMAGE_ANALYSIS".equals(normalizedIntent) && !safeImageIds.isEmpty()) {
      throw new BusinessRuleException("AI_IMAGES_NOT_ALLOWED", "仅图片分析可以携带图片");
    }
    if ("IMAGE_ANALYSIS".equals(normalizedIntent) && safeImageIds.isEmpty()) {
      throw new BusinessRuleException("AI_IMAGES_REQUIRED", "图片分析至少需要一张图片");
    }
    if (safeImageIds.size() > 10
        || safeImageIds.stream().distinct().count() != safeImageIds.size()) {
      throw new BusinessRuleException("AI_IMAGES_INVALID", "图片数量超过限制或存在重复图片");
    }
    long totalImageBytes = 0;
    var images = new java.util.ArrayList<AiImage>();
    for (String imageId : safeImageIds) {
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
    List<Fact> facts =
        List.of(
            new Fact("billingPointCode", draft.billingPointCode()),
            new Fact("billingPointName", draft.billingPointName()),
            new Fact("period", draft.period()),
            new Fact("auditStatus", draft.auditStatus()));
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
    int version = draft.currentVersion();
    if (changed) {
      version++;
      List<String> nextImages =
          "IMAGE_ANALYSIS".equals(normalizedIntent)
              ? java.util.stream.Stream.concat(
                      draft.currentImageFileIds().stream(), safeImageIds.stream())
                  .distinct()
                  .toList()
              : draft.currentImageFileIds();
      updateDraft(draft.id(), updated, nextImages, version, actor.username(), expectedVersion);
    } else {
      lockVersion(draft.id(), expectedVersion);
    }
    return find(publicId, actor);
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
    return find(publicId, actor);
  }

  @Transactional(readOnly = true)
  public List<DraftVersion> versions(String publicId, CurrentUser actor) {
    Draft draft = find(publicId, actor);
    return List.of(
        new DraftVersion(
            draft.publicId(),
            draft.currentVersion(),
            "CURRENT",
            draft.sections(),
            draft.currentImageFileIds(),
            draft.updatedAt(),
            actor.username()));
  }

  @Transactional
  public Draft restore(
      String publicId, String versionPublicId, long expectedVersion, CurrentUser actor) {
    Draft draft = find(publicId, actor);
    ensureEditable(draft);
    requireExpectedVersion(draft, expectedVersion);
    if (!draft.publicId().equals(versionPublicId)) {
      throw new ResourceNotFoundException("草稿版本不存在");
    }
    return draft;
  }

  public String uploadImage(String publicId, MultipartFile image, CurrentUser actor) {
    find(publicId, actor);
    return storedFileService
        .storeUpload(image, java.util.Set.of("png", "jpg", "jpeg"), "DRAFT_IMAGE", actor.username())
        .publicId();
  }

  @Transactional
  public BusinessTask submitFormal(String publicId, long expectedVersion, CurrentUser actor) {
    Draft draft = find(publicId, actor);
    String businessKey = "FORMAL_REPORT:" + draft.billingPointPeriodId();
    var existing = taskRepository.findByTypeAndBusinessKey(TaskType.FORMAL_REPORT, businessKey);
    if (existing.isPresent()) {
      return existing.orElseThrow();
    }
    ensureEditable(draft);
    requireExpectedVersion(draft, expectedVersion);
    int transitioned =
        jdbcTemplate.update(
            """
        UPDATE report_draft SET status = 'GENERATING', updated_at = CURRENT_TIMESTAMP(3),
               updated_by = ?, version = version + 1
         WHERE id = ? AND version = ? AND status IN ('DRAFT', 'CORRECTING')
        """,
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
          writeJson(Map.of("draftId", publicId)),
          actor.username(),
          3);
    } catch (DuplicateKeyException exception) {
      return taskRepository
          .findByTypeAndBusinessKey(TaskType.FORMAL_REPORT, businessKey)
          .orElseThrow(() -> exception);
    }
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

  private void updateDraft(
      long draftId,
      ReportSections sections,
      List<String> imageFileIds,
      int version,
      String actor,
      long expectedEntityVersion) {
    requireSections(sections);
    int updated =
        jdbcTemplate.update(
            """
        UPDATE report_draft
           SET title = ?, situation = ?, analysis = ?, rectification = ?,
               current_image_file_ids_json = ?, current_version_no = ?,
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
                   d.created_at, d.updated_at, d.version,
                   s.public_id AS snapshot_public_id, s.billing_point_code,
                   s.billing_point_name, s.city_code, s.data_period,
                   COALESCE(a.audit_status, 'NOT_APPLICABLE') AS audit_status
              FROM report_draft d
              JOIN billing_point_snapshot s ON s.id = d.billing_point_snapshot_id
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
                    resultSet.getString("data_period"),
                    resultSet.getString("audit_status"),
                    resultSet.getString("status"),
                    new ReportSections(
                        resultSet.getString("title"),
                        resultSet.getString("situation"),
                        resultSet.getString("analysis"),
                        resultSet.getString("rectification")),
                    resultSet.getInt("current_version_no"),
                    readStringList(resultSet.getString("current_image_file_ids_json")),
                    resultSet.getString("formal_report_public_id"),
                    List.of(),
                    resultSet.getObject("created_at", LocalDateTime.class),
                    resultSet.getObject("updated_at", LocalDateTime.class),
                    resultSet.getLong("version")),
            publicId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new ResourceNotFoundException("报告工作稿"));
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
    if ("EDIT".equals(requested)
        || ("AUTO".equals(requested)
            && java.util.regex.Pattern.compile("(修改|补充|优化|重写|改成|替换)")
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
        || isBlank(sections.situation())
        || isBlank(sections.analysis())
        || isBlank(sections.rectification())) {
      throw new BusinessRuleException("REPORT_SECTIONS_REQUIRED", "报告标题和固定三段正文不能为空");
    }
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
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

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Draft state could not be serialized", exception);
    }
  }

  private List<String> readStringList(String json) {
    try {
      return objectMapper.readValue(json, STRING_LIST);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted draft images are invalid JSON", exception);
    }
  }

  public record Draft(
      long id,
      String publicId,
      String billingPointPeriodId,
      String billingPointCode,
      String billingPointName,
      String cityCode,
      String period,
      String auditStatus,
      String status,
      ReportSections sections,
      int currentVersion,
      List<String> currentImageFileIds,
      String formalReportId,
      List<DraftMessage> messages,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      long entityVersion) {}

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

  private record Snapshot(
      long id,
      String publicId,
      String billingPointCode,
      String billingPointName,
      String cityCode,
      String period,
      String auditStatus,
      String reportId) {}
}
