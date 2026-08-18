package com.threefees.report.application;

import com.threefees.ai.application.CityMemoryService;
import com.threefees.file.application.StoredFileService;
import com.threefees.file.domain.StoredFile;
import com.threefees.task.application.TaskExecutionException;
import com.threefees.task.application.TaskProcessor;
import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class HistoricalReportImportTaskProcessor implements TaskProcessor {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(HistoricalReportImportTaskProcessor.class);

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

    /*
     * 如果该报账点账期已经存在正式报告：
     *
     * 1. 把历史导入记录与现有正式报告关联；
     * 2. 城市记忆索引采用 best-effort；
     * 3. 即使城市记忆失败，也不能把已经存在的正式报告判定成导入失败。
     */
    String existing = reportService.existingReportForSnapshot(input.snapshotId());

    if (existing != null) {

      reportService.reconcileHistoricalImportWithExistingReport(
          input.id(), existing, task.createdBy());

      indexHistoricalReportSafely(existing, input);

      return result(existing);
    }

    historicalService.markProcessing(input.id(), task.createdBy());

    try {

      /*
       * =====================================================
       * 1. 读取用户上传的原始 Word
       * =====================================================
       */

      StoredFile source = storedFileService.find(input.sourceFilePublicId());

      byte[] sourceBytes = storedFileService.readBytes(source);

      /*
       * =====================================================
       * 2. 尝试生成在线预览
       * =====================================================
       *
       * 注意：
       *
       * Word 预览失败不能导致整个历史报告导入失败。
       *
       * 如果当前电脑没有 Office / WPS / LibreOffice，
       * 或者某些老 .doc 文件无法转换，
       * 则自动退化成提示页面。
       *
       * 原始 Word 文件仍然会正常保留。
       */

      String previewHtml = extractWordPreviewHtml(input, sourceBytes);

      /*
       * =====================================================
       * 3. 生成历史报告标题
       * =====================================================
       */

      String title = input.billingPointName() + "电费稽核历史报告";

      /*
       * =====================================================
       * 4. 正式写入 audit_report
       * =====================================================
       */

      var finalization =
          reportService.finalizeHistoricalReport(
              input.id(),
              input.snapshotId(),
              title,
              previewHtml,
              input.sourceWordFileId(),
              null,
              task.createdBy());

      /*
       * =====================================================
       * 5. 城市历史记忆
       * =====================================================
       *
       * 这里必须是 best-effort。
       *
       * 历史报告已经成功进入 audit_report 后，
       * 即使城市记忆索引失败，
       * 也不能把整个历史报告重新标记为导入失败。
       */

      indexHistoricalReportSafely(finalization.reportId(), input);

      updateHistoricalImageAnalysisSafely(finalization.reportId(), input);

      /*
       * =====================================================
       * 6. 返回成功结果
       * =====================================================
       */

      LOGGER.info(
          "Historical report import succeeded. "
              + "importId={} reportId={} billingPointCode={} period={} fileName={}",
          input.publicId(),
          finalization.reportId(),
          input.billingPointCode(),
          input.period(),
          input.originalName());

      return result(finalization.reportId());

    } catch (TaskExecutionException exception) {

      /*
       * 已经明确分类过的任务异常：
       *
       * 保留原始错误码和原始 retryable 设置，
       * 不再统一包装成 HISTORICAL_CONVERSION_FAILED。
       */

      LOGGER.error(
          "Historical report import task failed. "
              + "importId={} billingPointCode={} cityCode={} period={} fileName={} code={}",
          input.publicId(),
          input.billingPointCode(),
          input.cityCode(),
          input.period(),
          input.originalName(),
          exception.code(),
          exception);

      historicalService.markFailed(input.id(), exception.code(), task.createdBy());

      throw exception;

    } catch (IllegalArgumentException exception) {

      /*
       * 文件内容 / 参数 / 格式类问题通常是确定性问题，
       * 自动重试3遍没有意义。
       *
       * 所以 retryable=false。
       */

      LOGGER.error(
          "Historical report import validation/conversion failed. "
              + "importId={} billingPointCode={} cityCode={} period={} fileName={}",
          input.publicId(),
          input.billingPointCode(),
          input.cityCode(),
          input.period(),
          input.originalName(),
          exception);

      historicalService.markFailed(input.id(), "HISTORICAL_CONVERSION_FAILED", task.createdBy());

      throw new TaskExecutionException(
          "HISTORICAL_CONVERSION_FAILED", buildFailureMessage("历史报告文件处理失败", exception), false);

    } catch (RuntimeException exception) {

      /*
       * 这里表示：
       *
       * 数据库写入异常
       * 文件读取异常
       * 正式报告生成异常
       * 其他未预期异常
       *
       * 不应该全部伪装成“Word转换失败”。
       *
       * 同时先设置 retryable=false，
       * 避免当前确定性错误每2秒重复执行3次。
       */

      LOGGER.error(
          "Historical report import failed unexpectedly. "
              + "importId={} billingPointCode={} cityCode={} period={} fileName={}",
          input.publicId(),
          input.billingPointCode(),
          input.cityCode(),
          input.period(),
          input.originalName(),
          exception);

      historicalService.markFailed(input.id(), "HISTORICAL_IMPORT_FAILED", task.createdBy());

      throw new TaskExecutionException(
          "HISTORICAL_IMPORT_FAILED", buildFailureMessage("历史报告导入失败", exception), false);
    }
  }

  /**
   * 尝试读取 Word 内容并生成 HTML 在线预览。
   *
   * <p>预览失败只降级，不影响原始 Word 正式导入。
   */
  private String extractWordPreviewHtml(
      HistoricalReportService.HistoricalTaskInput input, byte[] sourceBytes) {

    try {

      String previewHtml =
          documentGenerator.extractWordPreviewHtml(sourceBytes, input.originalName());

      if (previewHtml != null && !previewHtml.isBlank()) {

        return previewHtml;
      }

      LOGGER.warn(
          "Historical Word preview is empty; falling back to download-only preview. "
              + "importId={} billingPointCode={} period={} fileName={}",
          input.publicId(),
          input.billingPointCode(),
          input.period(),
          input.originalName());

    } catch (RuntimeException exception) {

      /*
       * 这里只记录预览异常。
       *
       * 不向上抛，
       * 因为历史报告真正的源文件是用户上传的 Word，
       * 在线 HTML 预览只是辅助能力。
       */

      LOGGER.warn(
          "Historical Word preview generation failed; "
              + "falling back to download-only preview. "
              + "importId={} billingPointCode={} period={} fileName={}",
          input.publicId(),
          input.billingPointCode(),
          input.period(),
          input.originalName(),
          exception);
    }

    return """
        <div class="word-preview word-preview-empty">
          <p>当前报告暂无法完整在线预览，请下载原始 Word 查看。</p>
        </div>
        """;
  }

  /**
   * 城市历史报告索引。
   *
   * <p>这是附加能力，不允许反向影响正式历史报告导入结果。
   */
  private void indexHistoricalReportSafely(
      String reportId, HistoricalReportService.HistoricalTaskInput input) {

    try {

      cityMemoryService.indexHistoricalReport(reportId);

    } catch (RuntimeException exception) {

      LOGGER.error(
          "Historical report was imported successfully, "
              + "but city memory indexing failed. "
              + "reportId={} importId={} billingPointCode={} cityCode={} period={}",
          reportId,
          input.publicId(),
          input.billingPointCode(),
          input.cityCode(),
          input.period(),
          exception);
    }
  }

  /**
   * 历史报告图片分析状态写入。
   *
   * <p>历史报告按原始 Word 导入时不做 AI 图片分析。
   *
   * <p>该状态写入失败也不能影响正式历史报告导入。
   */
  private void updateHistoricalImageAnalysisSafely(
      String reportId, HistoricalReportService.HistoricalTaskInput input) {

    try {

      cityMemoryService.updateHistoricalImageAnalysis(
          reportId, 0, "SKIPPED", "历史报告按原始 Word 文件导入，未执行 AI 图片分析。", null);

    } catch (RuntimeException exception) {

      LOGGER.error(
          "Historical report was imported successfully, "
              + "but historical image analysis status update failed. "
              + "reportId={} importId={} billingPointCode={} cityCode={} period={}",
          reportId,
          input.publicId(),
          input.billingPointCode(),
          input.cityCode(),
          input.period(),
          exception);
    }
  }

  /**
   * 给前端返回比“重新上传 Word 文件”更有意义的信息。
   *
   * <p>完整异常堆栈仍然只记录在后端日志中。
   */
  private String buildFailureMessage(String prefix, RuntimeException exception) {

    String causeMessage = exception.getMessage();

    if (causeMessage == null || causeMessage.isBlank()) {

      return prefix + "，请查看后台日志获取具体原因";
    }

    /*
     * 避免把特别长的数据库异常直接塞给前端。
     */
    String normalized = causeMessage.replace('\r', ' ').replace('\n', ' ').trim();

    if (normalized.length() > 200) {
      normalized = normalized.substring(0, 200) + "...";
    }

    return prefix + "：" + normalized;
  }

  /** 读取任务 payload。 */
  private Payload payload(BusinessTask task) {

    try {

      return objectMapper.readValue(task.payloadJson(), Payload.class);

    } catch (JacksonException exception) {

      LOGGER.error(
          "Historical report task payload is invalid. taskId={} payload={}",
          task.publicId(),
          task.payloadJson(),
          exception);

      throw new TaskExecutionException("TASK_PAYLOAD_INVALID", "历史报告导入任务数据异常，请重新提交", false);
    }
  }

  /** 生成任务成功结果。 */
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
