package com.threefees.importing.application;

import com.threefees.audit.application.AuditRecalculationService;
import com.threefees.importing.domain.ImportBatch;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportActivationService {

  private final ImportBatchRepository batchRepository;
  private final AuditRecalculationService auditRecalculationService;
  private final ImportCrossDatasetValidator crossDatasetValidator;
  private final FormalImportTableWriter formalImportTableWriter;

  public ImportActivationService(
      ImportBatchRepository batchRepository,
      AuditRecalculationService auditRecalculationService,
      ImportCrossDatasetValidator crossDatasetValidator,
      FormalImportTableWriter formalImportTableWriter) {
    this.batchRepository = batchRepository;
    this.auditRecalculationService = auditRecalculationService;
    this.crossDatasetValidator = crossDatasetValidator;
    this.formalImportTableWriter = formalImportTableWriter;
  }

  @Transactional
  public void replaceActivateAndRecalculate(ImportBatch batch, List<ImportRow> rows) {
    crossDatasetValidator.validate(batch, rows);
    formalImportTableWriter.replace(batch, rows);
    batchRepository.markSucceeded(batch, rows.size());
    if (batchRepository.allDatasetsActive(batch.period(), batch.cityCode())) {
      auditRecalculationService.recalculate(batch.period(), batch.cityCode());
    }
  }
}


