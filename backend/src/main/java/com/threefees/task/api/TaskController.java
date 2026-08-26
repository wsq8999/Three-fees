package com.threefees.task.api;

import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.application.ResourceConflictException;
import com.threefees.identity.application.ResourceNotFoundException;
import com.threefees.identity.domain.Role;
import com.threefees.report.application.ReportDraftService;
import com.threefees.task.application.BusinessTaskRepository;
import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskStatus;
import com.threefees.task.domain.TaskType;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

  private final BusinessTaskRepository repository;
  private final ReportDraftService draftService;
  private final ObjectMapper objectMapper;
  private final JdbcTemplate jdbcTemplate;

  public TaskController(
      BusinessTaskRepository repository,
      ReportDraftService draftService,
      ObjectMapper objectMapper,
      JdbcTemplate jdbcTemplate) {
    this.repository = repository;
    this.draftService = draftService;
    this.objectMapper = objectMapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  @GetMapping
  public TaskPage list(
      @RequestParam(required = false) TaskStatus status,
      @RequestParam(required = false) String billingPointName,
      @RequestParam(required = false) String cityName,
      @RequestParam(required = false) String period,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @AuthenticationPrincipal CurrentUser actor) {
    int safePage = Math.max(0, page);
    int safeSize = Math.max(1, Math.min(size, 100));
    List<TaskListItem> baseItems =
        loadAiImageTasks(status, actor).stream()
            .filter(item -> item.draftStatus() != TaskDraftStatus.FINALIZED)
            .toList();
    List<TaskListItem> filtered =
        baseItems.stream()
            .filter(item -> matchesField(item.billingPointName(), billingPointName))
            .filter(item -> matchesField(item.cityName(), cityName))
            .filter(item -> matchesField(item.period(), period))
            .sorted(Comparator.comparing(TaskListItem::updatedAt).reversed())
            .toList();
    int from = Math.min(safePage * safeSize, filtered.size());
    int to = Math.min(from + safeSize, filtered.size());
    return new TaskPage(
        filtered.subList(from, to),
        safePage,
        safeSize,
        filtered.size(),
        filtered.isEmpty() ? 0 : (int) Math.ceil(filtered.size() / (double) safeSize),
        summary(filtered),
        filterOptions(baseItems));
  }

  @GetMapping("/{publicId}")
  public TaskResponse find(
      @PathVariable String publicId, @AuthenticationPrincipal CurrentUser actor) {
    BusinessTask task = findTask(publicId);
    requireScope(task, actor);
    return response(task);
  }

  @PostMapping("/{publicId}/retries")
  public ResponseEntity<TaskResponse> retry(
      @PathVariable String publicId, @AuthenticationPrincipal CurrentUser actor) {
    BusinessTask before = findTask(publicId);
    requireScope(before, actor);
    if (isInputFailure(before.errorCode())) {
      throw new ResourceConflictException("TASK_REQUIRES_NEW_INPUT", "该失败需要修正文件或输入后重新提交，不能原任务重试");
    }
    if (before.type() == TaskType.AI_IMAGE_ANALYSIS) {
      draftService.requeueImageAnalysisTask(before, actor);
    } else if (!repository.retry(publicId)) {
      throw new ResourceConflictException("TASK_NOT_RETRYABLE", "只有最终失败的任务可以重试");
    }
    BusinessTask task = findTask(publicId);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/tasks/" + publicId))
        .body(response(task));
  }

  private void requireScope(BusinessTask task, CurrentUser actor) {
    if (!actor.roles().contains(Role.SUPER_ADMIN) && !actor.username().equals(task.createdBy())) {
      throw new AccessDeniedException("Task is outside the current user's scope");
    }
  }

  private List<TaskListItem> loadAiImageTasks(TaskStatus status, CurrentUser actor) {
    var sql =
        new StringBuilder(
            """
            SELECT id, public_id, task_type, business_key, status, attempts, max_attempts,
                   next_run_at, lease_owner, lease_expires_at, payload_json, result_json,
                   error_code, created_at, created_by, updated_at, version
              FROM business_task
             WHERE task_type = ?
            """);
    var args = new ArrayList<Object>();
    args.add(TaskType.AI_IMAGE_ANALYSIS.name());
    if (!actor.roles().contains(Role.SUPER_ADMIN)) {
      sql.append(" AND created_by = ?");
      args.add(actor.username());
    }
    sql.append(" ORDER BY updated_at DESC, id DESC");
    Map<String, TaskListItem> deduped = new LinkedHashMap<>();
    jdbcTemplate.query(sql.toString(), this::mapTask, args.toArray()).stream()
        .map(this::listItem)
        .filter(Objects::nonNull)
        .forEach(item -> deduped.putIfAbsent(dedupeKey(item), item));
    return deduped.values().stream()
        .filter(item -> status == null || item.status() == status)
        .toList();
  }

  private String dedupeKey(TaskListItem item) {
    if (item.relatedDraftId() != null && !item.relatedDraftId().isBlank()) {
      return "DRAFT:" + item.relatedDraftId();
    }
    return "TASK:" + item.id();
  }

  private BusinessTask mapTask(java.sql.ResultSet resultSet, int rowNumber)
      throws java.sql.SQLException {
    return new BusinessTask(
        resultSet.getLong("id"),
        resultSet.getString("public_id"),
        TaskType.valueOf(resultSet.getString("task_type")),
        resultSet.getString("business_key"),
        TaskStatus.valueOf(resultSet.getString("status")),
        resultSet.getInt("attempts"),
        resultSet.getInt("max_attempts"),
        resultSet.getObject("next_run_at", LocalDateTime.class),
        resultSet.getString("lease_owner"),
        resultSet.getObject("lease_expires_at", LocalDateTime.class),
        resultSet.getString("payload_json"),
        resultSet.getString("result_json"),
        resultSet.getString("error_code"),
        resultSet.getObject("created_at", LocalDateTime.class),
        resultSet.getString("created_by"),
        resultSet.getObject("updated_at", LocalDateTime.class),
        resultSet.getLong("version"));
  }

  private TaskListItem listItem(BusinessTask task) {
    JsonNode payload = parse(task.payloadJson());
    JsonNode result = parse(task.resultJson());
    RelatedTaskTarget related = relatedTarget(task.type(), payload, result);
    if (task.type() == TaskType.AI_IMAGE_ANALYSIS
        && related.relatedDraftId() != null
        && !related.analysisSubmitted()) {
      return null;
    }
    boolean canRetry = task.status() == TaskStatus.FAILED && !isInputFailure(task.errorCode());
    TaskDraftStatus draftStatus = taskDraftStatus(task.status(), related);
    return new TaskListItem(
        task.publicId(),
        task.type(),
        task.status(),
        task.attempts(),
        task.maxAttempts(),
        task.errorCode(),
        result,
        task.createdBy(),
        task.createdAt(),
        task.updatedAt(),
        related.relatedDraftId(),
        related.relatedReportId(),
        related.billingPointName(),
        related.cityName(),
        related.period(),
        draftStatus,
        canRetry,
        task.status() == TaskStatus.FAILED && !canRetry ? "该失败需要修正材料后重新提交" : null);
  }

  private RelatedTaskTarget relatedTarget(TaskType type, JsonNode payload, JsonNode result) {
    String draftId = text(payload, "draftId");
    if (draftId == null) draftId = text(result, "draftId");
    RelatedTaskTarget draftTarget = draftTarget(draftId);
    return draftTarget == null
        ? new RelatedTaskTarget(null, null, null, null, null, null, true)
        : draftTarget;
  }

  private RelatedTaskTarget draftTarget(String draftId) {
    if (draftId == null || draftId.isBlank()) return null;
    return jdbcTemplate
        .query(
            """
            SELECT d.public_id AS draft_id, d.formal_report_public_id AS report_id,
                   d.status AS draft_record_status, d.analysis_status,
                   d.analysis_submitted_at,
                   s.billing_point_name, c.name AS city_name, s.data_period
              FROM report_draft d
              JOIN billing_point_snapshot s ON s.id = d.billing_point_snapshot_id
              JOIN city c ON c.code = s.city_code
             WHERE d.public_id = ?
            """,
            (rs, row) ->
                new RelatedTaskTarget(
                    rs.getString("draft_id"),
                    rs.getString("report_id"),
                    rs.getString("billing_point_name"),
                    rs.getString("city_name"),
                    rs.getString("data_period"),
                    resolveDraftStatus(
                        rs.getString("draft_record_status"), rs.getString("analysis_status")),
                    rs.getObject("analysis_submitted_at", LocalDateTime.class) != null),
            draftId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private TaskDraftStatus resolveDraftStatus(String draftRecordStatus, String analysisStatus) {
    if ("FORMALIZED".equals(draftRecordStatus)) {
      return TaskDraftStatus.FINALIZED;
    }
    return switch (analysisStatus == null ? "" : analysisStatus) {
      case "AI_ANALYZING" -> TaskDraftStatus.AI_ANALYZING;
      case "AI_COMPLETED_PENDING_CONFIRMATION" -> TaskDraftStatus.AI_COMPLETED;
      case "AI_FAILED" -> TaskDraftStatus.AI_FAILED;
      case "FORMALIZED" -> TaskDraftStatus.FINALIZED;
      default -> TaskDraftStatus.EDITING;
    };
  }

  private TaskDraftStatus taskDraftStatus(TaskStatus taskStatus, RelatedTaskTarget related) {
    if (related.draftStatus() != null) {
      return related.draftStatus();
    }
    return switch (taskStatus) {
      case QUEUED, RETRY_WAIT -> TaskDraftStatus.EDITING;
      case RUNNING -> TaskDraftStatus.AI_ANALYZING;
      case SUCCEEDED -> TaskDraftStatus.AI_COMPLETED;
      case FAILED -> TaskDraftStatus.AI_FAILED;
    };
  }

  private boolean matchesField(String value, String expected) {
    if (expected == null || expected.isBlank()) return true;
    return value != null
        && value.toLowerCase(Locale.ROOT).contains(expected.trim().toLowerCase(Locale.ROOT));
  }

  private TaskSummary summary(List<TaskListItem> items) {
    return new TaskSummary(
        count(items, TaskStatus.QUEUED),
        count(items, TaskStatus.RUNNING),
        count(items, TaskStatus.RETRY_WAIT),
        items.stream()
            .filter(
                item ->
                    item.status() == TaskStatus.SUCCEEDED
                        && item.draftStatus() == TaskDraftStatus.AI_COMPLETED)
            .count(),
        count(items, TaskStatus.FAILED));
  }

  private TaskFilterOptions filterOptions(List<TaskListItem> items) {
    return new TaskFilterOptions(
        items.stream()
            .map(TaskListItem::cityName)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .sorted()
            .toList(),
        items.stream()
            .map(TaskListItem::period)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .sorted(Comparator.reverseOrder())
            .toList());
  }

  private long count(List<TaskListItem> items, TaskStatus status) {
    return items.stream().filter(item -> item.status() == status).count();
  }

  private String text(JsonNode node, String field) {
    if (node == null || !node.has(field) || node.get(field).isNull()) return null;
    String value = node.path(field).asText();
    return value == null || value.isBlank() ? null : value;
  }

  private boolean isInputFailure(String code) {
    return code != null
        && (code.startsWith("IMPORT_VALIDATION")
            || code.equals("IMPORT_PREREQUISITE_MISSING")
            || code.equals("HISTORICAL_WORD_INVALID")
            || code.equals("HISTORICAL_REPORT_EMPTY")
            || code.equals("REPORT_IMAGE_INVALID")
            || code.equals("TASK_PAYLOAD_INVALID"));
  }

  private BusinessTask findTask(String publicId) {
    return repository
        .findByPublicId(publicId)
        .orElseThrow(() -> new ResourceNotFoundException("任务"));
  }

  private TaskResponse response(BusinessTask task) {
    return new TaskResponse(
        task.publicId(),
        task.type(),
        task.status(),
        task.attempts(),
        task.maxAttempts(),
        task.errorCode(),
        parse(task.resultJson()),
        task.createdAt(),
        task.updatedAt());
  }

  private JsonNode parse(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readTree(json);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted task result is invalid JSON", exception);
    }
  }

  public record TaskResponse(
      String id,
      TaskType type,
      TaskStatus status,
      int attempts,
      int maxAttempts,
      String errorCode,
      JsonNode result,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  public record TaskPage(
      List<TaskListItem> items,
      int page,
      int size,
      long totalElements,
      int totalPages,
      TaskSummary summary,
      TaskFilterOptions filterOptions) {}

  public record TaskListItem(
      String id,
      TaskType type,
      TaskStatus status,
      int attempts,
      int maxAttempts,
      String errorCode,
      JsonNode result,
      String createdBy,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      String relatedDraftId,
      String relatedReportId,
      String billingPointName,
      String cityName,
      String period,
      TaskDraftStatus draftStatus,
      boolean canRetry,
      String retryBlockedReason) {}

  public record TaskSummary(
      long queued,
      long running,
      long retryWait,
      long completedPendingConfirmation,
      long failed) {}

  public record TaskFilterOptions(List<String> cityNames, List<String> periods) {}

  public enum TaskDraftStatus {
    EDITING,
    AI_ANALYZING,
    AI_COMPLETED,
    AI_FAILED,
    FINALIZED
  }

  private record RelatedTaskTarget(
      String relatedDraftId,
      String relatedReportId,
      String billingPointName,
      String cityName,
      String period,
      TaskDraftStatus draftStatus,
      boolean analysisSubmitted) {}
}
