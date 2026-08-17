package com.threefees.report.application;

import com.threefees.ai.application.CityMemoryService;
import com.threefees.file.application.StoredFileService;
import com.threefees.file.domain.StoredFile;
import com.threefees.report.application.ReportDocumentGenerator.ReportImage;
import com.threefees.task.application.TaskExecutionException;
import com.threefees.task.application.TaskProcessor;
import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskType;
import java.util.ArrayList;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class FormalReportTaskProcessor implements TaskProcessor {

  private final ObjectMapper objectMapper;
  private final AuditReportService reportService;
  private final ReportDocumentGenerator documentGenerator;
  private final StoredFileService storedFileService;
  private final CityMemoryService cityMemoryService;

  public FormalReportTaskProcessor(
      ObjectMapper objectMapper,
      AuditReportService reportService,
      ReportDocumentGenerator documentGenerator,
      StoredFileService storedFileService,
      CityMemoryService cityMemoryService) {
    this.objectMapper = objectMapper;
    this.reportService = reportService;
    this.documentGenerator = documentGenerator;
    this.storedFileService = storedFileService;
    this.cityMemoryService = cityMemoryService;
  }

  @Override
  public TaskType taskType() {
    return TaskType.FORMAL_REPORT;
  }

  @Override
  public String process(BusinessTask task) {
    String draftId = payload(task).draftId();
    AuditReportService.GenerationInput input = reportService.generationInput(draftId);
    boolean correction = input.formalReportId() != null && !input.formalReportId().isBlank();
    if (!correction) {
      String existing = reportService.existingReportForSnapshot(input.snapshotId());
      if (existing != null) {
        reportService.reconcileDraftWithExistingReport(input.draftId(), existing, task.createdBy());
        cityMemoryService.confirmGeneratedReport(draftId, existing, task.createdBy());
        return result(existing);
      }
    }
    reportService.beginOrResumeGeneration(draftId, task.createdBy());
    StoredFile word = null;
    StoredFile pdf = null;
    boolean finalized = false;
    try {
      var images = new ArrayList<ReportImage>();
      for (String fileId : input.imageFileIds()) {
        StoredFile file = storedFileService.find(fileId);
        if (!("image/png".equals(file.mediaType()) || "image/jpeg".equals(file.mediaType()))) {
          throw new TaskExecutionException("REPORT_IMAGE_INVALID", "报告图片格式不正确", false);
        }
        images.add(
            new ReportImage(
                fileId,
                file.originalName(),
                file.mediaType(),
                storedFileService.readBytes(file)));
      }
      var generated = documentGenerator.generate(input.sections(), images);
      byte[] wordBytes =
          correction && looksLikeHtml(input.sections().situation())
              ? documentGenerator.generateWordFromHtml(input.sections().situation(), images)
              : generated.word();
      word =
          storedFileService.storeGenerated(
              wordBytes,
              input.billingPointCode() + "-" + input.period() + "-电费稽核报告.docx",
              "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
              "FORMAL_REPORT_WORD",
              task.createdBy());
      pdf =
          storedFileService.storeGenerated(
              generated.pdf(),
              input.billingPointCode() + "-" + input.period() + "-电费稽核报告.pdf",
              "application/pdf",
              "FORMAL_REPORT_PDF",
              task.createdBy());
      var finalization =
          correction
              ? reportService.finalizeCorrectionReport(input, word.id(), pdf.id(), task.createdBy())
              : reportService.finalizeSystemReport(input, word.id(), pdf.id(), task.createdBy());
      finalized = finalization.created();
      cityMemoryService.confirmGeneratedReport(draftId, finalization.reportId(), task.createdBy());
      if (!finalization.created()) {
        storedFileService.deleteGenerated(pdf);
        pdf = null;
        storedFileService.deleteGenerated(word);
        word = null;
      }
      return result(finalization.reportId());
    } catch (TaskExecutionException exception) {
      compensate(pdf, word, finalized);
      resetIfFinal(task, draftId, exception.retryable());
      throw exception;
    } catch (RuntimeException exception) {
      compensate(pdf, word, finalized);
      resetIfFinal(task, draftId, true);
      throw new TaskExecutionException("FORMAL_REPORT_GENERATION_FAILED", "正式报告生成失败", true);
    }
  }

  private void resetIfFinal(BusinessTask task, String draftId, boolean retryable) {
    if (!retryable || task.attempts() >= task.maxAttempts()) {
      reportService.resetFailedGeneration(draftId, task.createdBy());
    }
  }

  private Payload payload(BusinessTask task) {
    try {
      return objectMapper.readValue(task.payloadJson(), Payload.class);
    } catch (JacksonException exception) {
      throw new TaskExecutionException("TASK_PAYLOAD_INVALID", "正式报告任务载荷不正确", false);
    }
  }

  private String result(String reportId) {
    try {
      return objectMapper.writeValueAsString(new Result(reportId));
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Formal report task result could not be serialized", exception);
    }
  }

  private boolean looksLikeHtml(String value) {
    return value != null
        && java.util.regex.Pattern.compile(
                "(?is)</?(div|p|table|tr|td|th|figure|img|section|article|h[1-6]|ul|ol|li)\\b")
            .matcher(value)
            .find();
  }

  private void compensate(StoredFile pdf, StoredFile word, boolean finalized) {
    if (finalized) {
      return;
    }
    if (pdf != null) {
      safelyDelete(pdf);
    }
    if (word != null) {
      safelyDelete(word);
    }
  }

  private void safelyDelete(StoredFile file) {
    try {
      storedFileService.deleteGenerated(file);
    } catch (RuntimeException ignored) {
      // Compensation is best-effort; the business report transaction remains authoritative.
    }
  }

  private record Payload(String draftId) {}

  private record Result(String reportId) {}
}
