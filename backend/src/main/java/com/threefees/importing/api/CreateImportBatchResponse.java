package com.threefees.importing.api;

import java.util.List;

public record CreateImportBatchResponse(List<ImportBatchResponse> batches) {

  public CreateImportBatchResponse {
    batches = List.copyOf(batches);
  }
}
