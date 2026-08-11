package com.threefees.importing.application;

import com.threefees.audit.application.AuditRecalculationService;
import com.threefees.importing.application.ImportBatchRepository.ImportedRow;
import com.threefees.importing.domain.ImportBatch;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportActivationService {

  private final ImportBatchRepository batchRepository;
  private final AuditRecalculationService auditRecalculationService;
  private final ImportCrossDatasetValidator crossDatasetValidator;

  public ImportActivationService(
      ImportBatchRepository batchRepository,
      AuditRecalculationService auditRecalculationService,
      ImportCrossDatasetValidator crossDatasetValidator) {
    this.batchRepository = batchRepository;
    this.auditRecalculationService = auditRecalculationService;
    this.crossDatasetValidator = crossDatasetValidator;
  }

  @Transactional
  public void replaceActivateAndRecalculate(ImportBatch batch, List<ImportedRow> rows) {
    batchRepository.replaceRows(batch.id(), rows);
    crossDatasetValidator.validate(batch, rows);
    batchRepository.activate(batch, rows);
    auditRecalculationService.recalculate(batch.period(), batch.cityCode());
  }
}
