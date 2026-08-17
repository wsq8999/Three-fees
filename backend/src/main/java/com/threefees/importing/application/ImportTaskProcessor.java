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
    if (!batchRepository.prerequisitesActive(batch)) {
      var errors =
          List.of(
              new ImportError(
                  0,
                  "datasetType",
                  "IMPORT_PREREQUISITE_MISSING",
                  "请按报账点清单、缴费明细、电表读数、标杆值的依赖顺序导入"));
      batchRepository.markFailed(batch.id(), errors);
      throw new TaskExecutionException(
          "IMPORT_PREREQUISITE_MISSING", errors.getFirst().message(), false);
    }
    try {
      var storedFile = storedFileService.find(batch.sourceFileId());
      TabularData data =
          tabularFileReader.read(
              storedFileService.readBytes(storedFile), storedFile.originalName());
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
                                  "Import file does not contain rows for this batch scope"))));
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
      batchRepository.markFailed(
          batch.id(),
          List.of(
              new ImportError(
                  0,
                  "system",
                  "IMPORT_PROCESSING_FAILED",
                  exception.getMessage() == null
                      ? "导入处理失败，可通过任务重试"
                      : exception.getMessage())));
      throw new TaskExecutionException("IMPORT_PROCESSING_FAILED", exception.getMessage(), true);
    }
  }

  private String payloadBatchId(String json) {
    try {
      String batchId = objectMapper.readTree(json).path("batchId").asText();
      if (batchId.isBlank()) {
        throw new IllegalStateException("Import task payload does not contain batchId");
      }
      return batchId;
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
}
