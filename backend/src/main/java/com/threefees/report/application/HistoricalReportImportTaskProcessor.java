package com.threefees.report.application;

import com.threefees.ai.application.CityMemoryService;
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
  private final CityMemoryService cityMemoryService;

  public HistoricalReportImportTaskProcessor(
      ObjectMapper objectMapper,
      HistoricalReportService historicalService,
      AuditReportService reportService,
      ReportDocumentGenerator documentGenerator,
      StoredFileService storedFileService,
      CityMemoryService cityMemoryService) {
    this.objectMapper = objectMapper;
    this.historicalService = historicalService;
    this.reportService = reportService;
    this.documentGenerator = documentGenerator;
    this.storedFileService = storedFileService;
    this.cityMemoryService = cityMemoryService;
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
      cityMemoryService.indexHistoricalReport(existing);
      return result(existing);
    }
    historicalService.markProcessing(input.id(), task.createdBy());
    try {
      StoredFile source = storedFileService.find(input.sourceFilePublicId());
      byte[] sourceBytes = storedFileService.readBytes(source);
      String previewHtml = extractWordPreviewHtml(input, sourceBytes, task.createdBy());
      String title = input.billingPointName() + "电费稽核历史报告";
      var finalization =
          reportService.finalizeHistoricalReport(
              input.id(),
              input.snapshotId(),
              title,
              previewHtml,
              input.sourceWordFileId(),
              null,
              task.createdBy());
      cityMemoryService.indexHistoricalReport(finalization.reportId());
      cityMemoryService.updateHistoricalImageAnalysis(
          finalization.reportId(), 0, "SKIPPED", "历史报告按原始 Word 文件导入，未执行 AI 图片分析。", null);
      return result(finalization.reportId());
    } catch (TaskExecutionException exception) {
      historicalService.markFailed(input.id(), exception.code(), task.createdBy());
      throw exception;
    } catch (IllegalArgumentException exception) {
      historicalService.markFailed(input.id(), "HISTORICAL_CONVERSION_FAILED", task.createdBy());
      throw new TaskExecutionException(
          "HISTORICAL_CONVERSION_FAILED", "历史报告导入失败，请重新上传 Word 文件", true);
    } catch (RuntimeException exception) {
      historicalService.markFailed(input.id(), "HISTORICAL_CONVERSION_FAILED", task.createdBy());
      throw new TaskExecutionException(
          "HISTORICAL_CONVERSION_FAILED", "历史报告导入失败，请重新上传 Word 文件", true);
    }
  }

  private String extractWordPreviewHtml(
      HistoricalReportService.HistoricalTaskInput input, byte[] sourceBytes, String actor) {
    try {
      String previewHtml =
          documentGenerator.extractWordPreviewHtml(sourceBytes, input.originalName());
      if (!previewHtml.isBlank()) {
        return previewHtml;
      }
    } catch (RuntimeException exception) {
      // Keep historical imports reliable on machines without Office/WPS/LibreOffice.
      // The uploaded Word remains the source file; online preview can degrade gracefully.
    }
    return """
        <div class="word-preview word-preview-empty">
          <p>当前报告暂无法完整在线预览，请下载原始 Word 查看。</p>
        </div>
        """;
  }

  private Payload payload(BusinessTask task) {
    try {
      return objectMapper.readValue(task.payloadJson(), Payload.class);
    } catch (JacksonException exception) {
      throw new TaskExecutionException("TASK_PAYLOAD_INVALID", "历史报告导入任务数据异常，请重新提交", false);
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

  private record Payload(String historicalImportId) {}

  private record Result(String reportId) {}
}
