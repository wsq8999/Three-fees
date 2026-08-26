package com.threefees.importing.api;

import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ImportBatch;
import com.threefees.importing.domain.ImportBatchStatus;
import com.threefees.importing.domain.ImportError;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

public record ImportBatchResponse(
    String id,
    DatasetType datasetType,
    String period,
    LocalDate periodStart,
    LocalDate periodEnd,
    String cityCode,
    ImportBatchStatus status,
    String taskId,
    int rowCount,
    int errorCount,
    List<ImportError> errors,
    LocalDateTime activatedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {

  static ImportBatchResponse from(ImportBatch batch) {
    return new ImportBatchResponse(
        batch.publicId(),
        batch.datasetType(),
        batch.period(),
        batch.periodStart(),
        batch.periodEnd(),
        batch.cityCode(),
        batch.status(),
        batch.taskPublicId(),
        batch.rowCount(),
        batch.errorCount(),
        batch.errors().stream().map(ImportBatchResponse::sanitizeError).toList(),
        batch.activatedAt(),
        batch.createdAt(),
        batch.updatedAt());
  }

  private static ImportError sanitizeError(ImportError error) {
    String code = error.code();
    String message = error.message() == null ? "" : error.message();
    String normalized = message.toLowerCase(Locale.ROOT);
    boolean databaseConflict =
        "IMPORT_CONCURRENT_DATABASE_CONFLICT".equals(code)
            || normalized.contains("deadlock found")
            || normalized.contains("preparedstatementcallback")
            || normalized.contains("insert into audit_result")
            || normalized.contains("cannotacquirelock")
            || normalized.contains("deadlockloser");
    if (databaseConflict) {
      return new ImportError(
          error.row(),
          error.column(),
          "IMPORT_PROCESSING_FAILED",
          "导入处理失败，请重试失败项。");
    }
    return error;
  }
}
