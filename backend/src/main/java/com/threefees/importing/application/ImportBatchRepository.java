package com.threefees.importing.application;

import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ImportBatch;
import com.threefees.importing.domain.ImportError;
import java.util.List;
import java.util.Optional;

public interface ImportBatchRepository {

  ImportBatch create(
      String publicId,
      DatasetType datasetType,
      String period,
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

  void markProcessing(long id);

  void replaceRows(long batchId, List<ImportedRow> rows);

  void activate(ImportBatch batch, List<ImportedRow> rows);

  void markFailed(long id, List<ImportError> errors);

  record ImportedRow(
      int sourceRow,
      String cityCode,
      String billingPointCode,
      String billingPointName,
      String paymentCode,
      String meterCode,
      String businessKey,
      String valuesJson) {}
}
