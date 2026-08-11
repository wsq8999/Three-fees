package com.threefees.importing.api;

import com.threefees.file.application.StoredFileService;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.application.ResourceNotFoundException;
import com.threefees.identity.domain.Role;
import com.threefees.importing.application.ExportCommandService;
import com.threefees.importing.application.ExportJobRepository;
import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ExportJob;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/export-jobs")
public class ExportJobController {

  private final ExportCommandService commandService;
  private final ExportJobRepository jobRepository;
  private final StoredFileService storedFileService;

  public ExportJobController(
      ExportCommandService commandService,
      ExportJobRepository jobRepository,
      StoredFileService storedFileService) {
    this.commandService = commandService;
    this.jobRepository = jobRepository;
    this.storedFileService = storedFileService;
  }

  @PostMapping
  public ResponseEntity<ExportJobResponse> create(
      @Valid @RequestBody CreateExportJobRequest request,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @AuthenticationPrincipal CurrentUser actor) {
    ExportJob job =
        commandService.submit(
            request.period(),
            request.cityCode(),
            request.resolvedDatasetTypes(),
            request.billingPointIds(),
            idempotencyKey,
            actor);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/export-jobs/" + job.publicId()))
        .body(response(job));
  }

  @GetMapping("/{publicId}")
  public ExportJobResponse find(
      @PathVariable String publicId, @AuthenticationPrincipal CurrentUser actor) {
    ExportJob job = findJob(publicId);
    if (!actor.roles().contains(Role.SUPER_ADMIN) && !actor.cityCode().equals(job.cityCode())) {
      throw new AccessDeniedException("Export job is outside city scope");
    }
    return response(job);
  }

  private ExportJob findJob(String publicId) {
    return jobRepository
        .findByPublicId(publicId)
        .orElseThrow(() -> new ResourceNotFoundException("导出任务"));
  }

  private ExportJobResponse response(ExportJob job) {
    String fileId =
        job.resultFileId() == null ? null : storedFileService.find(job.resultFileId()).publicId();
    return new ExportJobResponse(
        job.publicId(),
        job.period(),
        job.cityCode(),
        job.datasetTypes(),
        job.billingPointIds(),
        job.taskPublicId(),
        job.status(),
        job.errorCode(),
        fileId,
        fileId == null ? null : "/api/v1/files/" + fileId,
        job.createdAt(),
        job.updatedAt());
  }

  public record ExportJobResponse(
      String id,
      String period,
      String cityCode,
      List<DatasetType> datasetTypes,
      List<String> billingPointIds,
      String taskId,
      String status,
      String errorCode,
      String fileId,
      String downloadUrl,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}
}
