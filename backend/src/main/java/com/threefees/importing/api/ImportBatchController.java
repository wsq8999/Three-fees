package com.threefees.importing.api;

import com.threefees.identity.application.BusinessRuleException;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.application.ResourceConflictException;
import com.threefees.identity.application.ResourceNotFoundException;
import com.threefees.identity.domain.Role;
import com.threefees.importing.application.ImportBatchRepository;
import com.threefees.importing.application.ImportCommandService;
import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ImportBatch;
import com.threefees.task.application.BusinessTaskRepository;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1/import-batches")
public class ImportBatchController {

  private final ImportBatchRepository batchRepository;
  private final ImportCommandService commandService;
  private final BusinessTaskRepository taskRepository;

  public ImportBatchController(
      ImportBatchRepository batchRepository,
      ImportCommandService commandService,
      BusinessTaskRepository taskRepository) {
    this.batchRepository = batchRepository;
    this.commandService = commandService;
    this.taskRepository = taskRepository;
  }

  @GetMapping
  public ImportBatchPageResponse findPage(
      @RequestParam(required = false) DatasetType datasetType,
      @RequestParam(required = false) @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String period,
      @RequestParam(required = false) String cityCode,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @AuthenticationPrincipal CurrentUser actor) {
    String scope = scope(actor, cityCode);
    long total = batchRepository.count(datasetType, period, scope);
    int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
    var items =
        batchRepository
            .findPage(datasetType, period, scope, Math.multiplyExact(page, size), size)
            .stream()
            .map(ImportBatchResponse::from)
            .toList();
    return new ImportBatchPageResponse(items, page, size, total, totalPages);
  }

  @GetMapping("/{publicId}")
  public ImportBatchResponse find(
      @PathVariable String publicId, @AuthenticationPrincipal CurrentUser actor) {
    ImportBatch batch = findBatch(publicId);
    requireScope(batch, actor);
    return ImportBatchResponse.from(batch);
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<CreateImportBatchResponse> create(
      @RequestParam String datasetType,
      @RequestParam(required = false) @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String period,
      @RequestParam(required = false) String cityCode,
      @RequestParam MultipartFile file,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @AuthenticationPrincipal CurrentUser actor) {
    var batches =
        commandService.submit(
            parseDatasetType(datasetType), period, cityCode, file, idempotencyKey, actor);
    ImportBatch first = batches.getFirst();
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/tasks/" + first.taskPublicId()))
        .body(new CreateImportBatchResponse(batches.stream().map(ImportBatchResponse::from).toList()));
  }

  private DatasetType parseDatasetType(String value) {
    try {
      return DatasetType.valueOf(value == null ? "" : value.trim());
    } catch (IllegalArgumentException exception) {
      throw new BusinessRuleException("DATASET_TYPE_INVALID", "数据类型不正确");
    }
  }

  @PostMapping("/{publicId}/retries")
  public ResponseEntity<ImportBatchResponse> retry(
      @PathVariable String publicId, @AuthenticationPrincipal CurrentUser actor) {
    ImportBatch batch = findBatch(publicId);
    requireScope(batch, actor);
    boolean transientFailure =
        batch.errors().stream().allMatch(error -> "IMPORT_PROCESSING_FAILED".equals(error.code()));
    if (!transientFailure) {
      throw new ResourceConflictException("IMPORT_REQUIRES_NEW_FILE", "校验失败需要修正文件后创建新的导入批次");
    }
    if (!taskRepository.retry(batch.taskPublicId())) {
      throw new ResourceConflictException("IMPORT_BATCH_NOT_RETRYABLE", "只有最终失败的导入批次可以重试");
    }
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/tasks/" + batch.taskPublicId()))
        .body(ImportBatchResponse.from(batch));
  }

  private ImportBatch findBatch(String publicId) {
    return batchRepository
        .findByPublicId(publicId)
        .orElseThrow(() -> new ResourceNotFoundException("导入批次"));
  }

  private String scope(CurrentUser actor, String requestedCityCode) {
    if (!actor.roles().contains(Role.SUPER_ADMIN)) {
      if (requestedCityCode != null
          && !requestedCityCode.isBlank()
          && !requestedCityCode.equals(actor.cityCode())) {
        throw new AccessDeniedException("City scope mismatch");
      }
      return actor.cityCode();
    }
    return requestedCityCode;
  }

  private void requireScope(ImportBatch batch, CurrentUser actor) {
    if (!actor.roles().contains(Role.SUPER_ADMIN) && !actor.cityCode().equals(batch.cityCode())) {
      throw new AccessDeniedException("Import batch is outside city scope");
    }
  }
}
