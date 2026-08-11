package com.threefees.report.application;

import com.threefees.file.application.StoredFileService;
import com.threefees.file.domain.StoredFile;
import com.threefees.task.application.TaskExecutionException;
import com.threefees.task.application.TaskProcessor;
import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class HistoricalReportImportTaskProcessor implements TaskProcessor {

  private final ObjectMapper objectMapper;
  private final HistoricalReportService historicalService;
  private final AuditReportService reportService;
  private final ReportDocumentGenerator documentGenerator;
  private final StoredFileService storedFileService;

  public HistoricalReportImportTaskProcessor(
      ObjectMapper objectMapper,
      HistoricalReportService historicalService,
      AuditReportService reportService,
      ReportDocumentGenerator documentGenerator,
      StoredFileService storedFileService) {
    this.objectMapper = objectMapper;
    this.historicalService = historicalService;
    this.reportService = reportService;
    this.documentGenerator = documentGenerator;
    this.storedFileService = storedFileService;
  }

  @Override
  public TaskType taskType() {
    return TaskType.HISTORICAL_REPORT_IMPORT;
  }

  @Override
  public String process(BusinessTask task) {
    String importId = payload(task).historicalImportId();
    var input = historicalService.taskInput(importId);
    String existing = reportService.existingReportForSnapshot(input.snapshotId());
    if (existing != null) {
      reportService.reconcileHistoricalImportWithExistingReport(
          input.id(), existing, task.createdBy());
      return result(existing);
    }
    historicalService.markProcessing(input.id(), task.createdBy());
    StoredFile pdf = null;
    boolean finalized = false;
    try {
      StoredFile source = storedFileService.find(input.sourceFilePublicId());
      String text =
          documentGenerator.extractWordText(
              storedFileService.readBytes(source), input.originalName());
      if (text.isBlank()) {
        throw new TaskExecutionException("HISTORICAL_REPORT_EMPTY", "历史 Word 不包含可预览正文", false);
      }
      String title = input.billingPointName() + "物业电费稽核历史报告";
      byte[] pdfBytes = documentGenerator.generateHistoricalPdf(title, text);
      pdf =
          storedFileService.storeGenerated(
              pdfBytes,
              input.billingPointCode() + "-" + input.period() + "-历史报告.pdf",
              "application/pdf",
              "HISTORICAL_REPORT_PDF",
              task.createdBy());
      var finalization =
          reportService.finalizeHistoricalReport(
              input.id(),
              input.snapshotId(),
              title,
              text,
              input.sourceWordFileId(),
              pdf.id(),
              task.createdBy());
      finalized = finalization.created();
      if (!finalization.created()) {
        storedFileService.deleteGenerated(pdf);
        pdf = null;
      }
      return result(finalization.reportId());
    } catch (TaskExecutionException exception) {
      compensate(pdf, finalized);
      historicalService.markFailed(input.id(), exception.code(), task.createdBy());
      throw exception;
    } catch (IllegalArgumentException exception) {
      compensate(pdf, finalized);
      historicalService.markFailed(input.id(), "HISTORICAL_WORD_INVALID", task.createdBy());
      throw new TaskExecutionException("HISTORICAL_WORD_INVALID", "历史 Word 无法解析", false);
    } catch (RuntimeException exception) {
      compensate(pdf, finalized);
      historicalService.markFailed(input.id(), "HISTORICAL_CONVERSION_FAILED", task.createdBy());
      throw new TaskExecutionException("HISTORICAL_CONVERSION_FAILED", "历史报告转换失败", true);
    }
  }

  private Payload payload(BusinessTask task) {
    try {
      return objectMapper.readValue(task.payloadJson(), Payload.class);
    } catch (JacksonException exception) {
      throw new TaskExecutionException("TASK_PAYLOAD_INVALID", "历史报告任务载荷不正确", false);
    }
  }

  private String result(String reportId) {
    try {
      return objectMapper.writeValueAsString(new Result(reportId));
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Historical report result could not be serialized", exception);
    }
  }

  private void compensate(StoredFile pdf, boolean finalized) {
    if (pdf != null && !finalized) {
      try {
        storedFileService.deleteGenerated(pdf);
      } catch (RuntimeException ignored) {
        // Best-effort cleanup; no report row is committed on failure.
      }
    }
  }

  private record Payload(String historicalImportId) {}

  private record Result(String reportId) {}
}
