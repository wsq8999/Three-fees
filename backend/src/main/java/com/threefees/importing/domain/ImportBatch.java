package com.threefees.importing.domain;

import java.time.LocalDateTime;
import java.util.List;

public record ImportBatch(
    long id,
    String publicId,
    DatasetType datasetType,
    String period,
    String cityCode,
    ImportBatchStatus status,
    long sourceFileId,
    String taskPublicId,
    int rowCount,
    int errorCount,
    List<ImportError> errors,
    LocalDateTime activatedAt,
    LocalDateTime createdAt,
    String createdBy,
    LocalDateTime updatedAt,
    long version) {

  public ImportBatch {
    errors = List.copyOf(errors);
  }
}
