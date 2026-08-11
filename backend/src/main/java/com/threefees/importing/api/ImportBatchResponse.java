package com.threefees.importing.api;

import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ImportBatch;
import com.threefees.importing.domain.ImportBatchStatus;
import com.threefees.importing.domain.ImportError;
import java.time.LocalDateTime;
import java.util.List;

public record ImportBatchResponse(
    String id,
    DatasetType datasetType,
    String period,
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
        batch.cityCode(),
        batch.status(),
        batch.taskPublicId(),
        batch.rowCount(),
        batch.errorCount(),
        batch.errors(),
        batch.activatedAt(),
        batch.createdAt(),
        batch.updatedAt());
  }
}
