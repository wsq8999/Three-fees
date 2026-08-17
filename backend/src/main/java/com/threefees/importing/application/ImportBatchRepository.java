package com.threefees.importing.application;

import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ImportBatch;
import com.threefees.importing.domain.ImportError;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ImportBatchRepository {

  ImportBatch create(
      String publicId,
      DatasetType datasetType,
      String period,
      LocalDate periodStart,
      LocalDate periodEnd,
      String cityCode,
      long sourceFileId,
      String taskPublicId,
      String actor);

  Optional<ImportBatch> findByPublicId(String publicId);

  Optional<ImportBatch> findById(long id);

  List<ImportBatch> findPage(
      DatasetType datasetType, String period, String cityCode, int offset, int limit);

  long count(DatasetType datasetType, String period, String cityCode);

  boolean prerequisitesActive(ImportBatch batch);

  boolean allDatasetsActive(String period, String cityCode);

  Optional<String> findActiveCityForPayment(
      String period, String billingPointCode, String paymentCode);

  Optional<String> findActiveCityForBillingPoint(String period, String billingPointCode);

  void markProcessing(long id);

  void markSucceeded(ImportBatch batch, int rowCount);

  void markFailed(long id, List<ImportError> errors);
}


