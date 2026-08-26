package com.threefees.importing.api;

import com.threefees.importing.domain.DatasetType;
import java.util.List;

public record CreateExportJobRequest(
    String period,
    String cityCode,
    List<DatasetType> datasetTypes,
    DatasetType datasetType,
    List<String> billingPointIds) {

  public List<DatasetType> resolvedDatasetTypes() {
    if (datasetTypes != null && !datasetTypes.isEmpty()) {
      return datasetTypes;
    }
    return datasetType == null ? List.of() : List.of(datasetType);
  }
}
