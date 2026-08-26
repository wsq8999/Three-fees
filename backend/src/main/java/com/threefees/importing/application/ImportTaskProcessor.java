package com.threefees.importing.application;

import com.threefees.file.application.StoredFileService;
import com.threefees.identity.application.BusinessRuleException;
import com.threefees.identity.application.ResourceNotFoundException;
import com.threefees.importing.application.ImportActivationService.ActivationItem;
import com.threefees.importing.domain.ImportError;
import com.threefees.importing.domain.ImportBatch;
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
  private static final Object IMPORT_PROCESS_MONITOR = new Object();

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
    synchronized (IMPORT_PROCESS_MONITOR) {
      return processSequentially(task);
    }
  }

  private String processSequentially(BusinessTask task) {

    ImportTaskPayload payload = readPayload(task.payloadJson());
    if (payload.items() != null && !payload.items().isEmpty()) {
      return processFileTask(payload, task);
    }

    String batchPublicId = payload.batchId();

    var batch = batchRepository.findByPublicId(batchPublicId).orElseThrow();

    batchRepository.markProcessing(batch.id());

    /*
     * 最终业务规则：
     *
     * 1. 报账点清单必须先导入；
     * 2. 缴费明细、电表读数、标杆值三者平级；
     * 3. 后三类文件彼此之间没有导入顺序要求。
     *
     * DatasetType 中后三类的 prerequisites 都只包含 BILLING_POINT。
     */
    if (!batchRepository.prerequisitesActive(batch)) {

      List<ImportError> errors =
          List.of(
              new ImportError(
                  0,
                  "datasetType",
                  "IMPORT_PREREQUISITE_MISSING",
                  "请先导入报账点清单；缴费明细、电表读数、标杆值可任意顺序导入"));

      batchRepository.markFailed(batch.id(), errors);

      throw new TaskExecutionException(
          "IMPORT_PREREQUISITE_MISSING", errors.getFirst().message(), false);
    }

    try {

      /*
       * 新任务：
       *
       * ImportCommandService 已经在上传阶段解析过一次文件，
       * 并把当前城市 + 当前账期对应的 rows 保存到了任务 payload。
       *
       * 因此直接使用 payload.rows()，
       * 不再重新读取整个 CSV / Excel。
       */
      List<ImportRow> rows =
          payload.rows() == null || payload.rows().isEmpty()
              ? loadRowsFromSource(batch)
              : List.copyOf(payload.rows());

      /*
       * 最终校验 + 正式表替换 + 激活 + 必要时重新稽核。
       */
      importActivationService.replaceActivateAndRecalculate(batch, rows);

      return writeJson(Map.of("batchId", batch.publicId(), "status", "ACTIVE"));

    } catch (ImportValidationException exception) {

      batchRepository.markFailed(batch.id(), exception.errors());

      throw new TaskExecutionException("IMPORT_VALIDATION_FAILED", exception.getMessage(), false);

    } catch (BusinessRuleException exception) {

      batchRepository.markFailed(
          batch.id(),
          List.of(new ImportError(0, "file", exception.code(), exception.getMessage())));

      throw new TaskExecutionException(exception.code(), exception.getMessage(), false);

    } catch (TaskExecutionException exception) {

      throw exception;

    } catch (RuntimeException exception) {

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

  private String processFileTask(ImportTaskPayload payload, BusinessTask task) {
    List<ImportTaskItem> payloadItems = List.copyOf(payload.items());
    List<ImportBatch> batches =
        payloadItems.stream()
            .map(
                item ->
                    batchRepository
                        .findByPublicId(item.batchId())
                        .orElseThrow(() -> new ResourceNotFoundException("import batch")))
            .toList();

    for (ImportBatch batch : batches) {
      batchRepository.markProcessing(batch.id());
    }

    try {
      for (ImportBatch batch : batches) {
        if (!batchRepository.prerequisitesActive(batch)) {
          List<ImportError> errors =
              List.of(
                  new ImportError(
                      0,
                      "datasetType",
                      "IMPORT_PREREQUISITE_MISSING",
                      "请先导入报账点清单；缴费明细、电表读数、标杆值可任意顺序导入"));
          markFailed(batches, errors);
          throw new TaskExecutionException(
              "IMPORT_PREREQUISITE_MISSING", errors.getFirst().message(), false);
        }
      }

      List<ActivationItem> activationItems = new java.util.ArrayList<>();
      for (int index = 0; index < batches.size(); index++) {
        ImportBatch batch = batches.get(index);
        ImportTaskItem item = payloadItems.get(index);
        List<ImportRow> rows =
            item.rows() == null || item.rows().isEmpty()
                ? loadRowsFromSource(batch)
                : List.copyOf(item.rows());
        importActivationService.preflightValidate(batch, rows);
        batchRepository.markPreflightCompleted(batch.id(), rows.size());
        activationItems.add(new ActivationItem(batch, rows));
      }

      importActivationService.replaceActivateAndRecalculateAll(activationItems);

      return writeJson(
          Map.of(
              "batchIds",
              batches.stream().map(ImportBatch::publicId).toList(),
              "status",
              "ACTIVE"));

    } catch (ImportValidationException exception) {

      markFailed(batches, exception.errors());

      throw new TaskExecutionException("IMPORT_VALIDATION_FAILED", exception.getMessage(), false);

    } catch (BusinessRuleException exception) {

      markFailed(batches, List.of(new ImportError(0, "file", exception.code(), exception.getMessage())));

      throw new TaskExecutionException(exception.code(), exception.getMessage(), false);

    } catch (TaskExecutionException exception) {

      throw exception;

    } catch (RuntimeException exception) {

      LOGGER.error(
          "Import file processing failed taskId={} batchCount={}",
          task.publicId(),
          batches.size(),
          exception);

      String message =
          exception.getMessage() == null || exception.getMessage().isBlank()
              ? "导入处理失败，可通过任务重试"
              : exception.getMessage();

      markFailed(batches, List.of(new ImportError(0, "system", "IMPORT_PROCESSING_FAILED", message)));

      throw new TaskExecutionException("IMPORT_PROCESSING_FAILED", message, true);
    }
  }

  private void markFailed(List<ImportBatch> batches, List<ImportError> errors) {
    for (ImportBatch batch : batches) {
      batchRepository.markFailed(batch.id(), errors);
    }
  }

  /**
   * 兼容旧任务。
   *
   * <p>代码升级前已经存在于数据库中的 IMPORT 任务 payload 只有 batchId， 没有 rows。
   *
   * <p>对于这些历史任务继续使用旧逻辑：
   *
   * <p>读取原始文件 -> 自动识别 Sheet / 表头 -> 映射 -> 找到当前城市和账期。
   *
   * <p>这样升级代码以后不需要删除旧任务。
   */
  private List<ImportRow> loadRowsFromSource(com.threefees.importing.domain.ImportBatch batch) {

    var storedFile = storedFileService.find(batch.sourceFileId());

    TabularData data =
        tabularFileReader.read(
            storedFileService.readBytes(storedFile),
            storedFile.originalName(),
            batch.datasetType());

    return importRowMapper.mapAuto(batch.datasetType(), null, null, data).stream()
        .filter(
            group ->
                group.cityCode().equals(batch.cityCode()) && group.period().equals(batch.period()))
        .findFirst()
        .map(ImportRowGroup::rows)
        .orElseThrow(
            () ->
                new ImportValidationException(
                    List.of(
                        new ImportError(
                            0, "datasetType", "IMPORT_SCOPE_MISMATCH", "导入文件中不存在当前批次城市和账期对应的数据"))));
  }

  /**
   * 读取任务 payload。
   *
   * <p>旧格式：
   *
   * <pre>
   * {"batchId":"..."}
   * </pre>
   *
   * <p>新格式：
   *
   * <pre>
   * {"batchId":"...","rows":[...]}
   * </pre>
   */
  private ImportTaskPayload readPayload(String json) {

    try {
      ImportTaskPayload payload = objectMapper.readValue(json, ImportTaskPayload.class);

      boolean hasSingleBatch = payload != null && payload.batchId() != null && !payload.batchId().isBlank();
      boolean hasFileItems = payload != null && payload.items() != null && !payload.items().isEmpty();

      if (!hasSingleBatch && !hasFileItems) {

        throw new IllegalStateException("Import task payload does not contain batchId");
      }

      return payload;

    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted import task payload is invalid", exception);
    }
  }

  private String writeJson(Object value) {

    try {
      return objectMapper.writeValueAsString(value);

    } catch (JacksonException exception) {
      throw new IllegalStateException("Import result could not be serialized", exception);
    }
  }

  /**
   * rows 允许为 null：
   *
   * <p>用于兼容升级前只有 batchId 的旧任务。
   */
  private record ImportTaskPayload(String batchId, List<ImportRow> rows, List<ImportTaskItem> items) {}

  private record ImportTaskItem(String batchId, List<ImportRow> rows) {}
}
