package com.threefees.report.api;

import com.threefees.identity.application.CurrentUser;
import com.threefees.report.application.ReportGenerationService;
import com.threefees.report.application.ReportGenerationService.AnalyzeImageCommand;
import com.threefees.report.application.ReportGenerationService.AnalyzeImageResult;
import com.threefees.report.application.ReportGenerationService.Candidate;
import com.threefees.report.application.ReportGenerationService.CorrectionGenerateCommand;
import com.threefees.report.application.ReportGenerationService.GenerateReportCommand;
import com.threefees.report.application.ReportGenerationService.GeneratedReport;
import com.threefees.report.application.ReportGenerationService.InitialContent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class ReportGenerationController {

  private final ReportGenerationService service;

  public ReportGenerationController(ReportGenerationService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/report-generation/candidates")
  public List<Candidate> candidates(
      @RequestParam(required = false) String cityCode, @AuthenticationPrincipal CurrentUser actor) {
    return service.candidates(cityCode, actor);
  }

  @GetMapping("/api/v1/report-generation/initial-content")
  public InitialContent initialContent(
      @RequestParam @NotBlank String billingPointCode,
      @RequestParam @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String period,
      @AuthenticationPrincipal CurrentUser actor) {
    return service.initialContent(billingPointCode, period, actor);
  }

  @GetMapping("/api/v1/report-generation/corrections/{reportId}/initial-content")
  public InitialContent correctionInitialContent(
      @PathVariable String reportId, @AuthenticationPrincipal CurrentUser actor) {
    return service.correctionInitialContent(reportId, actor);
  }

  @PostMapping("/api/v1/report-generation/formal-reports")
  public GeneratedReport generate(
      @Valid @RequestBody GenerateRequest request, @AuthenticationPrincipal CurrentUser actor) {
    return service.generate(
        new GenerateReportCommand(
            request.billingPointCode(), request.period(), request.contentHtml()),
        actor);
  }

  @PutMapping("/api/v1/report-generation/formal-reports/{reportId}")
  public GeneratedReport regenerate(
      @PathVariable String reportId,
      @Valid @RequestBody CorrectionGenerateRequest request,
      @AuthenticationPrincipal CurrentUser actor) {
    return service.regenerate(
        new CorrectionGenerateCommand(
            reportId,
            request.billingPointCode(),
            request.period(),
            request.contentHtml(),
            request.reason()),
        actor);
  }

  @PostMapping("/api/v1/report-generation/image-analysis")
  public AnalyzeImageResult analyzeImages(
      @Valid @RequestBody AnalyzeImageRequest request, @AuthenticationPrincipal CurrentUser actor) {
    return service.analyzeImages(
        new AnalyzeImageCommand(
            request.billingPointCode(),
            request.period(),
            request.contentHtml(),
            request.instruction(),
            request.images().stream()
                .map(
                    image ->
                        new AnalyzeImageCommand.ImageInput(
                            image.fileName(), image.mediaType(), image.base64Data()))
                .toList()),
        actor);
  }

  public record GenerateRequest(
      @NotBlank String billingPointCode,
      @NotBlank @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String period,
      @NotBlank @Size(max = 2_000_000) String contentHtml) {}

  public record CorrectionGenerateRequest(
      @NotBlank String billingPointCode,
      @NotBlank @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String period,
      @NotBlank @Size(max = 2_000_000) String contentHtml,
      @NotBlank @Size(max = 1_000) String reason) {}

  public record AnalyzeImageRequest(
      @NotBlank String billingPointCode,
      @NotBlank @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String period,
      @NotBlank @Size(max = 2_000_000) String contentHtml,
      @Size(max = 4_000) String instruction,
      @NotNull @Size(min = 1) List<AnalyzeImage> images) {}

  public record AnalyzeImage(
      @NotBlank @Size(max = 255) String fileName,
      @NotBlank @Pattern(regexp = "image/(png|jpeg)") String mediaType,
      @NotBlank @Size(max = 14_000_000) String base64Data) {}
}
