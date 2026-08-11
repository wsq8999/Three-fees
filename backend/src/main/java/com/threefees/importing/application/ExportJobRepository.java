package com.threefees.importing.application;

import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ExportJob;
import java.util.List;
import java.util.Optional;

public interface ExportJobRepository {

  ExportJob create(
      String publicId,
      String period,
      String cityCode,
      List<DatasetType> datasetTypes,
      List<String> billingPointIds,
      String taskPublicId,
      String actor);

  Optional<ExportJob> findByPublicId(String publicId);

  void markProcessing(long id);

  void markSucceeded(long id, long resultFileId);

  void markFailed(long id, String errorCode);
}
