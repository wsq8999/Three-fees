package com.threefees.importing.domain;

import java.time.LocalDateTime;
import java.util.List;

public record ExportJob(
    long id,
    String publicId,
    String period,
    String cityCode,
    List<DatasetType> datasetTypes,
    List<String> billingPointIds,
    String taskPublicId,
    String status,
    Long resultFileId,
    String errorCode,
    LocalDateTime createdAt,
    String createdBy,
    LocalDateTime updatedAt) {

  public ExportJob {
    datasetTypes = List.copyOf(datasetTypes);
    billingPointIds = List.copyOf(billingPointIds);
  }
}
