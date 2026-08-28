package com.threefees.report.api;

import com.threefees.identity.application.CurrentUser;
import com.threefees.report.application.AuditReportService;
import com.threefees.report.application.AuditReportService.HistoricalBillingPointOption;
import com.threefees.report.application.AuditReportService.HistoricalCandidate;
import com.threefees.report.application.AuditReportService.HistoricalPeriodOption;
import com.threefees.report.application.AuditReportService.ReportDetail;
import com.threefees.report.application.AuditReportService.ReportPage;
import com.threefees.report.application.HistoricalReportService;
import com.threefees.report.application.HistoricalReportService.HistoricalImport;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
public class AuditReportController {

  private final AuditReportService reportService;
  private final HistoricalReportService historicalService;

  public AuditReportController(
      AuditReportService reportService, HistoricalReportService historicalService) {
    this.reportService = reportService;
    this.historicalService = historicalService;
  }

    @GetMapping("/api/v1/reports")
    public ReportPage reports(
        @RequestParam(required = false) String reportNumber,
        @RequestParam(required = false) String billingPointCode,
        @RequestParam(required = false) String billingPointName,
        @RequestParam(required = false)
        @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])")
        String period,
        @RequestParam(required = false) String cityCode,
        @RequestParam(required = false) String district,
        @RequestParam(required = false) String source,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
        @AuthenticationPrincipal CurrentUser actor) {

        return reportService.findPage(
            reportNumber,
            billingPointCode,
            billingPointName,
            period,
            cityCode,
            district,
            source,
            page,
            size,
            actor);
    }

  @GetMapping("/api/v1/reports/filter-options")
  public AuditReportService.ReportFilterOptions reportFilterOptions(
      @RequestParam(required = false) String reportNumber,
      @RequestParam(required = false) String billingPointCode,
      @RequestParam(required = false) String billingPointName,
      @RequestParam(required = false) @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String period,
      @RequestParam(required = false) String cityCode,
      @RequestParam(required = false) String source,
      @AuthenticationPrincipal CurrentUser actor) {
    return reportService.filterOptions(
        reportNumber,
        billingPointCode,
        billingPointName,
        period,
        cityCode,
        source,
        actor);
  }

  @GetMapping("/api/v1/reports/{publicId}")
  public ResponseEntity<ReportDetail> report(
      @PathVariable String publicId, @AuthenticationPrincipal CurrentUser actor) {
    ReportDetail detail = reportService.find(publicId, actor);
    return ResponseEntity.ok().eTag(Long.toString(detail.version())).body(detail);
  }

  @GetMapping("/api/v1/reports/{publicId}/word")
  public ResponseEntity<InputStreamResource> word(
      @PathVariable String publicId,
      @RequestParam(defaultValue = "false") boolean inline,
      @AuthenticationPrincipal CurrentUser actor) {
    return file(publicId, false, inline, actor);
  }

  @GetMapping("/api/v1/reports/{publicId}/pdf")
  public ResponseEntity<InputStreamResource> pdf(
      @PathVariable String publicId,
      @RequestParam(defaultValue = "true") boolean inline,
      @AuthenticationPrincipal CurrentUser actor) {
    return file(publicId, true, inline, actor);
  }

  @GetMapping("/api/v1/historical-report-candidates")
  public List<HistoricalCandidate> historicalCandidates(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String cityCode,
      @AuthenticationPrincipal CurrentUser actor) {
    return reportService.historicalCandidates(keyword, cityCode, actor);
  }

  @GetMapping("/api/v1/historical-report-billing-points")
  public List<HistoricalBillingPointOption> historicalBillingPoints(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String cityCode,
      @AuthenticationPrincipal CurrentUser actor) {
    return reportService.historicalBillingPoints(keyword, cityCode, actor);
  }

  @GetMapping("/api/v1/historical-report-billing-points/{billingPointCode}/periods")
  public List<HistoricalPeriodOption> historicalPeriods(
      @PathVariable String billingPointCode,
      @RequestParam(required = false) String cityCode,
      @AuthenticationPrincipal CurrentUser actor) {
    return reportService.historicalPeriods(billingPointCode, cityCode, actor);
  }

  @PostMapping(
      value = "/api/v1/historical-report-imports",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<HistoricalImport> importHistorical(
      @RequestParam(required = false) String billingPointPeriodId,
      @RequestParam(required = false) String billingPointCode,
      @RequestParam(required = false) String cityCode,
      @RequestParam(required = false) @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String period,
      @RequestParam MultipartFile file,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @AuthenticationPrincipal CurrentUser actor) {
    HistoricalImport created =
        billingPointCode != null && !billingPointCode.isBlank() && period != null
            ? historicalService.submitByBillingPointPeriod(
                billingPointCode, cityCode, period, file, idempotencyKey, actor)
            : historicalService.submit(billingPointPeriodId, file, idempotencyKey, actor);
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/tasks/" + created.taskId()))
        .body(created);
  }

  @GetMapping("/api/v1/historical-report-imports/{publicId}")
  public HistoricalImport historicalImport(
      @PathVariable String publicId, @AuthenticationPrincipal CurrentUser actor) {
    return historicalService.find(publicId, actor);
  }

  private ResponseEntity<InputStreamResource> file(
      String reportId, boolean pdf, boolean inline, CurrentUser actor) {
    var access = reportService.reportFile(reportId, pdf, actor);
    ContentDisposition disposition =
        inline
            ? ContentDisposition.inline()
                .filename(access.downloadName(), StandardCharsets.UTF_8)
                .build()
            : ContentDisposition.attachment()
                .filename(access.downloadName(), StandardCharsets.UTF_8)
                .build();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(access.file().mediaType()))
        .contentLength(access.file().byteSize())
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(access.resource());
  }
}
