package com.threefees.importing.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threefees.audit.application.AuditRecalculationService;
import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ImportBatch;
import com.threefees.importing.domain.ImportBatchStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImportActivationServiceTest {

  @Test
  void billingPointImportDoesNotTriggerAuditRecalculation() {
    ImportBatchRepository batchRepository = mock(ImportBatchRepository.class);
    AuditRecalculationService auditRecalculationService = mock(AuditRecalculationService.class);
    ImportCrossDatasetValidator crossDatasetValidator = mock(ImportCrossDatasetValidator.class);
    FormalImportTableWriter formalImportTableWriter = mock(FormalImportTableWriter.class);
    var service =
        new ImportActivationService(
            batchRepository,
            auditRecalculationService,
            crossDatasetValidator,
            formalImportTableWriter);
    ImportBatch batch = batch(DatasetType.BILLING_POINT);
    List<ImportRow> rows = List.of(row());

    service.replaceActivateAndRecalculate(batch, rows);

    verify(crossDatasetValidator).validate(batch, rows);
    verify(formalImportTableWriter).replace(batch, rows);
    verify(batchRepository).markSucceeded(batch, rows.size());
    verify(auditRecalculationService, never()).recalculate(any(), any(), any());
  }

  @Test
  void businessDataImportTriggersAuditRecalculationForAffectedBillingPoints() {
    ImportBatchRepository batchRepository = mock(ImportBatchRepository.class);
    AuditRecalculationService auditRecalculationService = mock(AuditRecalculationService.class);
    ImportCrossDatasetValidator crossDatasetValidator = mock(ImportCrossDatasetValidator.class);
    FormalImportTableWriter formalImportTableWriter = mock(FormalImportTableWriter.class);
    var service =
        new ImportActivationService(
            batchRepository,
            auditRecalculationService,
            crossDatasetValidator,
            formalImportTableWriter);
    ImportBatch batch = batch(DatasetType.PAYMENT);
    List<ImportRow> rows = List.of(row());

    service.replaceActivateAndRecalculate(batch, rows);

    verify(crossDatasetValidator).validate(batch, rows);
    verify(formalImportTableWriter).replace(batch, rows);
    verify(batchRepository).markSucceeded(batch, rows.size());
    verify(auditRecalculationService).recalculate("2026-01", "321200", Set.of("BP001"));
  }

  private ImportBatch batch(DatasetType datasetType) {
    return new ImportBatch(
        1L,
        "batch-1",
        datasetType,
        "2026-01",
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 31),
        "321200",
        ImportBatchStatus.PROCESSING,
        1L,
        "task-1",
        0,
        0,
        List.of(),
        null,
        LocalDateTime.now(),
        "admin",
        LocalDateTime.now(),
        1L);
  }

  private ImportRow row() {
    return new ImportRow(1, "321200", "BP001", "测试报账点", "PAY001", null, "key", "{}");
  }
}
