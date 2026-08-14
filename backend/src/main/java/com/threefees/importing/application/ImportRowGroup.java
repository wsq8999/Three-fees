package com.threefees.importing.application;

import com.threefees.importing.domain.DatasetType;
import java.util.List;

public record ImportRowGroup(
    DatasetType datasetType, String cityCode, String period, List<ImportRow> rows) {

  public ImportRowGroup {
    rows = List.copyOf(rows);
  }
}
