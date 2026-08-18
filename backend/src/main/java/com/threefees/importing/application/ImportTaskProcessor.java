package com.threefees.importing.application;

import com.threefees.file.application.StoredFileService;
import com.threefees.identity.application.BusinessRuleException;
import com.threefees.importing.domain.ImportError;
import com.threefees.task.application.TaskExecutionException;
import com.threefees.task.application.TaskProcessor;
import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskType;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class ImportTaskProcessor implements TaskProcessor {

  private static final Logger LOGGER = LoggerFactory.getLogger(ImportTaskProcessor.class);

  private final ImportBatchRepository batchRepository;
  private final StoredFileService storedFileService;
  private final TabularFileReader tabularFileReader;
  private final ImportRowMapper importRowMapper;
  private final ImportActivationService importActivationService;
  private final ObjectMapper objectMapper;

  public ImportTaskProcessor(
      ImportBatchRepository batchRepository,
      StoredFileService storedFileService,
      TabularFileReader tabularFileReader,
      ImportRowMapper importRowMapper,
      ImportActivationService importActivationService,
      ObjectMapper objectMapper) {

    this.batchRepository = batchRepository;
    this.storedFileService = storedFileService;
    this.tabularFileReader = tabularFileReader;
    this.importRowMapper = importRowMapper;
    this.importActivationService = importActivationService;
    this.objectMapper = objectMapper;
  }

  @Override
  public TaskType taskType() {
    return TaskType.IMPORT;
  }

  @Override
  public String process(BusinessTask task) {

    String batchPublicId = payloadBatchId(task.payloadJson());

    var batch = batchRepository.findByPublicId(batchPublicId).orElseThrow();

    batchRepository.markProcessing(batch.id());

    /*
     * 检查数据导入前置依赖。
     *
     * 例如：
     * 缴费明细依赖报账点；
     * 电表读数依赖缴费明细；
     * 标杆值依赖报账点。
     */
    if (!batchRepository.prerequisitesActive(batch)) {

      List<ImportError> errors =
          List.of(
              new ImportError(
                  0, "datasetType", "IMPORT_PREREQUISITE_MISSING", "请按报账点清单、缴费明细、电表读数、标杆值的依赖顺序导入"));

      batchRepository.markFailed(batch.id(), errors);

      throw new TaskExecutionException(
          "IMPORT_PREREQUISITE_MISSING", errors.getFirst().message(), false);
    }

    try {

      /*
       * 找到该导入批次对应的原始上传文件。
       */
      var storedFile = storedFileService.find(batch.sourceFileId());

      /*
       * 关键修改：
       *
       * 以前这里调用的是：
       *
       * read(bytes, fileName)
       *
       * 现在必须把 batch.datasetType() 一起传进去。
       *
       * 这样后台任务重新读取文件时，
       * TabularFileReader 才知道当前导入的是：
       *
       * BILLING_POINT
       * PAYMENT
       * METER_READING
       * BENCHMARK
       *
       * 才能够：
       *
       * 1. 自动遍历多个 Sheet；
       * 2. 自动找到真正的数据 Sheet；
       * 3. 自动找到真正的表头行；
       * 4. 不再固定读取第一个 Sheet。
       */
      TabularData data =
          tabularFileReader.read(
              storedFileService.readBytes(storedFile),
              storedFile.originalName(),
              batch.datasetType());

      /*
       * 再按照当前数据类型进行字段映射。
       *
       * ImportRowMapper 已经改为：
       *
       * 按表头名称映射字段，
       * 不再按固定列位置映射。
       */
      var rows =
          importRowMapper.mapAuto(batch.datasetType(), null, null, data).stream()
              .filter(
                  group ->
                      group.cityCode().equals(batch.cityCode())
                          && group.period().equals(batch.period()))
              .findFirst()
              .map(ImportRowGroup::rows)
              .orElseThrow(
                  () ->
                      new ImportValidationException(
                          List.of(
                              new ImportError(
                                  0,
                                  "datasetType",
                                  "IMPORT_SCOPE_MISMATCH",
                                  "导入文件中不存在当前批次城市和账期对应的数据"))));

      /*
       * 校验通过后：
       *
       * 替换当前批次数据，
       * 激活数据，
       * 并重新计算相关业务结果。
       */
      importActivationService.replaceActivateAndRecalculate(batch, rows);

      return writeJson(Map.of("batchId", batch.publicId(), "status", "ACTIVE"));

    } catch (ImportValidationException exception) {

      /*
       * 文件字段、数据内容等校验失败。
       */
      batchRepository.markFailed(batch.id(), exception.errors());

      throw new TaskExecutionException("IMPORT_VALIDATION_FAILED", exception.getMessage(), false);

    } catch (BusinessRuleException exception) {

      /*
       * 文件格式、Sheet识别、业务规则等失败。
       */
      batchRepository.markFailed(
          batch.id(),
          List.of(new ImportError(0, "file", exception.code(), exception.getMessage())));

      throw new TaskExecutionException(exception.code(), exception.getMessage(), false);

    } catch (TaskExecutionException exception) {

      throw exception;

    } catch (RuntimeException exception) {

      /*
       * 未预期的系统异常。
       *
       * 记录完整日志，
       * 同时把导入批次标记为失败。
       */
      LOGGER.error(
          "Import processing failed batchId={} datasetType={} period={} cityCode={}",
          batch.publicId(),
          batch.datasetType(),
          batch.period(),
          batch.cityCode(),
          exception);

      String message =
          exception.getMessage() == null || exception.getMessage().isBlank()
              ? "导入处理失败，可通过任务重试"
              : exception.getMessage();

      batchRepository.markFailed(
          batch.id(), List.of(new ImportError(0, "system", "IMPORT_PROCESSING_FAILED", message)));

      throw new TaskExecutionException("IMPORT_PROCESSING_FAILED", message, true);
    }
  }

  /** 从任务 payload 中读取导入批次 ID。 */
  private String payloadBatchId(String json) {

    try {

      String batchId = objectMapper.readTree(json).path("batchId").asText();

      if (batchId == null || batchId.isBlank()) {

        throw new IllegalStateException("Import task payload does not contain batchId");
      }

      return batchId;

    } catch (JacksonException exception) {

      throw new IllegalStateException("Persisted import task payload is invalid", exception);
    }
  }

  /** 将任务执行结果转换成 JSON。 */
  private String writeJson(Object value) {

    try {

      return objectMapper.writeValueAsString(value);

    } catch (JacksonException exception) {

      throw new IllegalStateException("Import result could not be serialized", exception);
    }
  }
}
