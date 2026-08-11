package com.threefees.importing.api;

import com.threefees.importing.domain.DatasetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record CreateExportJobRequest(
    @NotBlank @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String period,
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
