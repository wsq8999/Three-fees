package com.threefees.task.api;

import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.application.ResourceConflictException;
import com.threefees.identity.application.ResourceNotFoundException;
import com.threefees.identity.domain.Role;
import com.threefees.task.application.BusinessTaskRepository;
import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskStatus;
import com.threefees.task.domain.TaskType;
import java.net.URI;
import java.time.LocalDateTime;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

  private final BusinessTaskRepository repository;
  private final ObjectMapper objectMapper;

  public TaskController(BusinessTaskRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
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
    if (!repository.retry(publicId)) {
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
}
