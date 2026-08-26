package com.threefees.importing.application;

import com.threefees.audit.application.AuditRecalculationService;
import com.threefees.importing.domain.ImportBatch;
import com.threefees.importing.domain.DatasetType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    replaceActivateAndRecalculateAll(List.of(new ActivationItem(batch, rows)));
  }

  public void preflightValidate(ImportBatch batch, List<ImportRow> rows) {
    crossDatasetValidator.validate(batch, rows);
  }

  @Transactional
  public void replaceActivateAndRecalculateAll(List<ActivationItem> items) {
    for (ActivationItem item : items) {
      crossDatasetValidator.validate(item.batch(), item.rows());
      formalImportTableWriter.replace(item.batch(), item.rows());
      batchRepository.markSucceeded(item.batch(), item.rows().size());
    }
    for (ActivationItem item : items) {
      ImportBatch batch = item.batch();
      if (batch.datasetType() != DatasetType.BILLING_POINT) {
        auditRecalculationService.recalculate(
            batch.period(), batch.cityCode(), affectedCodes(item.rows()));
      }
    }
  }

  private Set<String> affectedCodes(List<ImportRow> rows) {
    var codes = new LinkedHashSet<String>();
    for (ImportRow row : rows) {
      if (row.billingPointCode() != null && !row.billingPointCode().isBlank()) {
        codes.add(row.billingPointCode());
      }
    }
    return codes;
  }

  public record ActivationItem(ImportBatch batch, List<ImportRow> rows) {}
}
