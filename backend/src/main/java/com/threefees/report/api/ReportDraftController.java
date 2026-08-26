package com.threefees.report.api;

import com.threefees.ai.application.AiServiceClient.ReportSections;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.application.ResourceConflictException;
import com.threefees.report.application.ReportDraftService;
import com.threefees.report.application.ReportDraftService.Draft;
import com.threefees.report.application.ReportDraftService.DraftMessage;
import com.threefees.report.application.ReportDraftService.DraftVersion;
import com.threefees.report.application.ReportDraftService.UploadedImage;
import com.threefees.task.domain.BusinessTask;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1/report-drafts")
public class ReportDraftController {

  private final ReportDraftService service;

  public ReportDraftController(ReportDraftService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<DraftResponse> createOrResume(
      @Valid @RequestBody CreateDraftRequest request, @AuthenticationPrincipal CurrentUser actor) {
    Draft draft = service.createOrResume(request.billingPointPeriodId(), actor);
    return draftResponse(draft)
        .location(URI.create("/api/v1/report-drafts/" + draft.publicId()))
        .body(DraftResponse.from(draft));
  }

  @PostMapping("/corrections/{reportId}")
  public ResponseEntity<DraftResponse> createCorrection(
      @PathVariable String reportId,
      @Valid @RequestBody CreateCorrectionDraftRequest request,
      @AuthenticationPrincipal CurrentUser actor) {
    Draft draft = service.createCorrection(reportId, request.reason(), actor);
    return draftResponse(draft)
        .location(URI.create("/api/v1/report-drafts/" + draft.publicId()))
        .body(DraftResponse.from(draft));
  }

  @DeleteMapping("/{publicId}/unused-correction")
  public ResponseEntity<DiscardCorrectionResponse> discardUnusedCorrection(
      @PathVariable String publicId, @AuthenticationPrincipal CurrentUser actor) {
    boolean discarded = service.discardUnusedCorrection(publicId, actor);
    return ResponseEntity.ok(new DiscardCorrectionResponse(discarded));
  }

  @GetMapping("/{publicId}")
  public ResponseEntity<DraftResponse> find(
      @PathVariable String publicId, @AuthenticationPrincipal CurrentUser actor) {
    Draft draft = service.find(publicId, actor);
    return draftResponse(draft).body(DraftResponse.from(draft));
  }

  @PatchMapping("/{publicId}")
  public ResponseEntity<DraftResponse> edit(
      @PathVariable String publicId,
      @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
      @Valid @RequestBody UpdateDraftRequest request,
      @AuthenticationPrincipal CurrentUser actor) {
    Draft draft = service.edit(publicId, request.toSections(), parseVersion(ifMatch), actor);
    return draftResponse(draft).body(DraftResponse.from(draft));
  }

  @PostMapping("/{publicId}/messages")
  public ResponseEntity<DraftResponse> assist(
      @PathVariable String publicId,
      @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
      @RequestHeader(name = "X-Trace-Id", required = false) String traceId,
      @Valid @RequestBody AssistanceRequest request,
      @AuthenticationPrincipal CurrentUser actor) {
    Draft draft =
        service.assist(
            publicId,
            request.intent(),
            request.content(),
            request.imageFileIds(),
            parseVersion(ifMatch),
            traceId == null ? "" : traceId,
            actor);
    return draftResponse(draft).body(DraftResponse.from(draft));
  }

  @PostMapping(value = "/{publicId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ImageUploadResponse> uploadImage(
      @PathVariable String publicId,
      @RequestParam("file") MultipartFile file,
      @AuthenticationPrincipal CurrentUser actor) {
    UploadedImage uploaded = service.uploadImage(publicId, file, actor);
    return ResponseEntity.created(URI.create("/api/v1/files/" + uploaded.fileId()))
        .body(new ImageUploadResponse(uploaded.fileId(), uploaded.entityVersion()));
  }

  @GetMapping("/{publicId}/images/{fileId}/content")
  public ResponseEntity<InputStreamResource> imageContent(
      @PathVariable String publicId,
      @PathVariable String fileId,
      @RequestParam(defaultValue = "true") boolean inline,
      @AuthenticationPrincipal CurrentUser actor) {
    var access = service.imageContent(publicId, fileId, actor);
    ContentDisposition disposition =
        inline
            ? ContentDisposition.inline()
                .filename(access.file().originalName(), StandardCharsets.UTF_8)
                .build()
            : ContentDisposition.attachment()
                .filename(access.file().originalName(), StandardCharsets.UTF_8)
                .build();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(access.file().mediaType()))
        .contentLength(access.file().byteSize())
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(access.resource());
  }

  @DeleteMapping("/{publicId}/images/{fileId}")
  public ResponseEntity<DraftResponse> removeImage(
      @PathVariable String publicId,
      @PathVariable String fileId,
      @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
      @AuthenticationPrincipal CurrentUser actor) {
    Draft draft = service.removeImage(publicId, fileId, parseVersion(ifMatch), actor);
    return draftResponse(draft).body(DraftResponse.from(draft));
  }

  @PutMapping("/{publicId}/images/order")
  public ResponseEntity<DraftResponse> reorderImages(
      @PathVariable String publicId,
      @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
      @Valid @RequestBody ImageOrderRequest request,
      @AuthenticationPrincipal CurrentUser actor) {
    Draft draft =
        service.reorderImages(publicId, request.imageFileIds(), parseVersion(ifMatch), actor);
    return draftResponse(draft).body(DraftResponse.from(draft));
  }

  @GetMapping("/{publicId}/versions")
  public List<DraftVersion> versions(
      @PathVariable String publicId, @AuthenticationPrincipal CurrentUser actor) {
    return service.versions(publicId, actor);
  }

  @PostMapping("/{publicId}/versions/{versionId}/restorations")
  public ResponseEntity<DraftResponse> restore(
      @PathVariable String publicId,
      @PathVariable String versionId,
      @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
      @AuthenticationPrincipal CurrentUser actor) {
    Draft draft = service.restore(publicId, versionId, parseVersion(ifMatch), actor);
    return draftResponse(draft).body(DraftResponse.from(draft));
  }

  @PostMapping("/{publicId}/formal-report")
  public ResponseEntity<TaskAcceptedResponse> submitFormal(
      @PathVariable String publicId,
      @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
      @AuthenticationPrincipal CurrentUser actor) {
    BusinessTask task = service.submitFormal(publicId, parseVersion(ifMatch), actor);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/tasks/" + task.publicId()))
        .body(new TaskAcceptedResponse(task.publicId(), task.type().name(), task.status().name()));
  }

  private ResponseEntity.BodyBuilder draftResponse(Draft draft) {
    return ResponseEntity.ok().eTag(Long.toString(draft.entityVersion()));
  }

  private long parseVersion(String ifMatch) {
    String value = ifMatch == null ? "" : ifMatch.trim();
    if (value.startsWith("W/")) {
      value = value.substring(2);
    }
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException exception) {
      throw new ResourceConflictException("IF_MATCH_INVALID", "If-Match 必须是当前资源版本号");
    }
  }

  public record CreateDraftRequest(@NotBlank String billingPointPeriodId) {}

  public record CreateCorrectionDraftRequest(@NotBlank @Size(max = 1_000) String reason) {}

  public record UpdateDraftRequest(
      @NotBlank @Size(max = 500) String title,
      @NotBlank @Size(max = 100_000) String situation,
      @NotNull @Size(max = 100_000) String analysis,
      @NotNull @Size(max = 100_000) String rectification) {

    ReportSections toSections() {
      return new ReportSections(title, situation, analysis, rectification);
    }
  }

  public record AssistanceRequest(
      @Size(max = 32) String intent,
      @NotBlank @Size(max = 4_000) String content,
      @NotNull List<String> imageFileIds) {}

  public record ImageUploadResponse(String fileId, long entityVersion) {}

  public record ImageOrderRequest(@NotNull List<String> imageFileIds) {}

  public record DiscardCorrectionResponse(boolean discarded) {}

  public record TaskAcceptedResponse(String taskId, String type, String status) {}

  public record DraftResponse(
      String id,
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
      List<com.threefees.report.application.ReportDraftService.OverLimitRatio> overLimitRatios,
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
      long version) {

    static DraftResponse from(Draft draft) {
      return new DraftResponse(
          draft.publicId(),
          draft.billingPointPeriodId(),
          draft.billingPointCode(),
          draft.billingPointName(),
          draft.cityCode(),
          draft.cityName(),
          draft.district(),
          draft.period(),
          draft.auditStatus(),
          draft.overLimitType(),
          draft.overLimitDisplayType(),
          draft.maxExceedRatio(),
          draft.overLimitRatios(),
          draft.status(),
          draft.sections(),
          draft.currentVersion(),
          draft.currentImageFileIds(),
          draft.formalReportId(),
          draft.analysisStatus(),
          draft.analysisTaskId(),
          draft.analysisErrorCode(),
          draft.analysisSubmittedAt(),
          draft.analysisCompletedAt(),
          draft.messages(),
          draft.createdAt(),
          draft.updatedAt(),
          draft.entityVersion());
    }
  }
}
