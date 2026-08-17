package com.threefees.report.application;

import com.threefees.ai.application.CityMemoryService;
import com.threefees.ai.application.AiServiceClient;
import com.threefees.ai.application.AiServiceException;
import com.threefees.file.application.StoredFileService;
import com.threefees.file.domain.StoredFile;
import com.threefees.task.application.TaskExecutionException;
import com.threefees.task.application.TaskProcessor;
import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskType;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class HistoricalReportImportTaskProcessor implements TaskProcessor {

  private static final Pattern EMBEDDED_IMAGE =
      Pattern.compile("(?is)<img[^>]+src=\\\"data:(image/[a-zA-Z0-9.+-]+);base64,([^\\\"]+)\\\"");

  private final ObjectMapper objectMapper;
  private final HistoricalReportService historicalService;
  private final AuditReportService reportService;
  private final ReportDocumentGenerator documentGenerator;
  private final StoredFileService storedFileService;
  private final CityMemoryService cityMemoryService;
  private final AiServiceClient aiServiceClient;

  public HistoricalReportImportTaskProcessor(
      ObjectMapper objectMapper,
      HistoricalReportService historicalService,
      AuditReportService reportService,
      ReportDocumentGenerator documentGenerator,
      StoredFileService storedFileService,
      CityMemoryService cityMemoryService,
      AiServiceClient aiServiceClient) {
    this.objectMapper = objectMapper;
    this.historicalService = historicalService;
    this.reportService = reportService;
    this.documentGenerator = documentGenerator;
    this.storedFileService = storedFileService;
    this.cityMemoryService = cityMemoryService;
    this.aiServiceClient = aiServiceClient;
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
      analyzeHistoricalImages(input, existing, null);
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
      analyzeHistoricalImages(input, finalization.reportId(), previewHtml);
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

  private void analyzeHistoricalImages(
      HistoricalReportService.HistoricalTaskInput input,
      String reportId,
      String knownPreviewHtml) {
    if (!aiServiceClient.isAvailable()) {
      cityMemoryService.updateHistoricalImageAnalysis(reportId, 0, "PENDING", null, null);
      return;
    }
    try {
      String previewHtml = knownPreviewHtml;
      if (previewHtml == null) {
        StoredFile source = storedFileService.find(input.sourceFilePublicId());
        previewHtml =
            documentGenerator.extractWordPreviewHtml(
                storedFileService.readBytes(source), input.originalName());
      }
      var matcher = EMBEDDED_IMAGE.matcher(previewHtml);
      List<AiServiceClient.AiImage> images = new ArrayList<>();
      while (matcher.find()) {
        images.add(
            new AiServiceClient.AiImage(
                "历史报告图片-" + (images.size() + 1),
                matcher.group(1),
                Base64.getDecoder().decode(matcher.group(2))));
      }
      if (images.isEmpty()) {
        cityMemoryService.updateHistoricalImageAnalysis(
            reportId, 0, "NO_IMAGES", "历史报告中未发现可提取图片。", null);
        return;
      }
      String safeHtml =
          previewHtml
              .replaceAll(
                  "(?is)data:image/[^;]+;base64,[A-Za-z0-9+/=]+", "[历史报告内嵌图片]");
      if (safeHtml.length() > 50_000) {
        safeHtml = safeHtml.substring(0, 50_000) + "…";
      }
      var analyses = new ArrayList<String>();
      for (int start = 0; start < images.size(); start += 10) {
        List<AiServiceClient.AiImage> batch =
            images.subList(start, Math.min(start + 10, images.size()));
        var result =
            aiServiceClient.analyzeReportImages(
                input.billingPointCode(),
                input.period(),
                safeHtml,
                "逐张提取历史稽核报告图片中的现场、设备、系统和用电证据，仅用于历史案例检索。",
                List.of(
                    new AiServiceClient.Fact("所属城市", input.cityCode()),
                    new AiServiceClient.Fact("报账点编码", input.billingPointCode()),
                    new AiServiceClient.Fact("账期", input.period())),
                batch,
                UUID.randomUUID().toString());
        analyses.add(result.analysisText());
      }
      cityMemoryService.updateHistoricalImageAnalysis(
          reportId, images.size(), "COMPLETED", String.join("\n", analyses), null);
    } catch (AiServiceException exception) {
      cityMemoryService.updateHistoricalImageAnalysis(
          reportId, 0, "FAILED", null, exception.code());
    } catch (RuntimeException exception) {
      cityMemoryService.updateHistoricalImageAnalysis(
          reportId, 0, "FAILED", null, "HISTORICAL_IMAGE_EXTRACTION_FAILED");
    }
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
