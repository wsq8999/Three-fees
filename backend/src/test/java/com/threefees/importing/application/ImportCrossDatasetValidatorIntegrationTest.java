package com.threefees.importing.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.threefees.ThreeFeesApplication;
import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ImportBatch;
import com.threefees.importing.domain.ImportBatchStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = ThreeFeesApplication.class,
    properties = {
      "app.bootstrap.enabled=true",
      "app.bootstrap.initial-password=test-password-123456"
    })
class ImportCrossDatasetValidatorIntegrationTest {

  @Autowired private ImportCrossDatasetValidator validator;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void billingPointImportDoesNotRequireAllExistingDependentPoints() {
    insertPayment("2026-10", "320100", "BP-OLD", "PAY-OLD");

    assertThatCode(
            () ->
                validator.validate(
                    batch(DatasetType.BILLING_POINT, "2026-10", "320100"),
                    List.of(row("BP-NEW", null))))
        .doesNotThrowAnyException();
  }

  @Test
  void paymentImportDoesNotNeedToCoverExistingMeterPayments() {
    insertMaster("320100", "BP-001");
    insertSnapshot("2026-11", "320100", "BP-001");
    insertMeter("2026-11", "320100", "BP-001", "PAY-OLD", "M-OLD");

    assertThatCode(
            () ->
                validator.validate(
                    batch(DatasetType.PAYMENT, "2026-11", "320100"),
                    List.of(row("BP-001", "PAY-NEW"))))
        .doesNotThrowAnyException();
  }

  @Test
  void paymentImportStillRejectsUnknownBillingPoint() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    batch(DatasetType.PAYMENT, "2026-12", "320100"),
                    List.of(row("BP-MISSING", "PAY-1"))))
        .isInstanceOf(ImportValidationException.class);
  }

  @Test
  void meterImportDoesNotRequireReferencedPaymentWhenBillingPointExists() {
    insertMaster("320100", "BP-001");
    insertSnapshot("2027-01", "320100", "BP-001");

    assertThatCode(
            () ->
                validator.validate(
                    batch(DatasetType.METER_READING, "2027-01", "320100"),
                    List.of(row("BP-001", "PAY-MISSING"))))
        .doesNotThrowAnyException();
  }

  @Test
  void meterImportStillRejectsUnknownBillingPoint() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    batch(DatasetType.METER_READING, "2027-02", "320100"),
                    List.of(row("BP-MISSING", "PAY-001"))))
        .isInstanceOf(ImportValidationException.class);
  }

  @Test
  void snapshotOnlyReferenceDoesNotSatisfyBillingPointMasterDependency() {
    insertSnapshot("2027-03", "320100", "BP-SNAPSHOT-ONLY");

    assertThatThrownBy(
            () ->
                validator.validate(
                    batch(DatasetType.PAYMENT, "2027-03", "320100"),
                    List.of(row("BP-SNAPSHOT-ONLY", "PAY-001"))))
        .isInstanceOf(ImportValidationException.class);
  }

  private void insertMaster(String cityCode, String billingPointCode) {
    jdbcTemplate.update(
        """
        INSERT INTO billing_point_master
          (billing_point_code, billing_point_name, city_code, resource_summary_json)
        VALUES (?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE
          billing_point_name = VALUES(billing_point_name),
          resource_summary_json = VALUES(resource_summary_json)
        """,
        billingPointCode,
        billingPointCode,
        cityCode,
        "{}");
  }

  private void insertSnapshot(String period, String cityCode, String billingPointCode) {
    jdbcTemplate.update(
        """
        INSERT INTO billing_point_snapshot
          (public_id, data_period, period_start, period_end, city_code, source_import_job_id,
           source_row_no, raw_row_json, billing_point_code, billing_point_name, city_name,
           district_name, data_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID().toString(),
        period,
        period + "-01",
        period + "-28",
        cityCode,
        1,
        2,
        "{}",
        billingPointCode,
        billingPointCode,
        cityCode,
        "district",
        "{}");
  }

  private void insertPayment(
      String period, String cityCode, String billingPointCode, String paymentCode) {
    jdbcTemplate.update(
        """
        INSERT INTO payment_detail
          (public_id, data_period, period_start, period_end, city_code, source_import_job_id,
           source_row_no, raw_row_json, payment_bill_code, city_name, billing_point_code,
           billing_point_name, payment_start, payment_end, values_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID().toString(),
        period,
        period + "-01",
        period + "-28",
        cityCode,
        1,
        2,
        "{}",
        paymentCode,
        cityCode,
        billingPointCode,
        billingPointCode,
        period + "-01",
        period + "-28",
        "{}");
  }

  private void insertMeter(
      String period,
      String cityCode,
      String billingPointCode,
      String paymentCode,
      String meterCode) {
    jdbcTemplate.update(
        """
        INSERT INTO meter_reading
          (public_id, data_period, period_start, period_end, city_code, source_import_job_id,
           source_row_no, raw_row_json, billing_point_code, payment_bill_code, payment_start,
           payment_end, meter_code, values_json)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID().toString(),
        period,
        period + "-01",
        period + "-28",
        cityCode,
        1,
        2,
        "{}",
        billingPointCode,
        paymentCode,
        period + "-01",
        period + "-28",
        meterCode,
        "{}");
  }

  private ImportBatch batch(DatasetType type, String period, String cityCode) {
    return new ImportBatch(
        999,
        "batch-" + type.name() + "-" + period + "-" + cityCode,
        type,
        period,
        LocalDate.parse(period + "-01"),
        LocalDate.parse(period + "-28"),
        cityCode,
        ImportBatchStatus.PROCESSING,
        1,
        "task-1",
        0,
        0,
        List.of(),
        null,
        LocalDateTime.now(),
        "test",
        LocalDateTime.now(),
        0);
  }

  private ImportRow row(String billingPointCode, String paymentCode) {
    return new ImportRow(
        2,
        "320100",
        billingPointCode,
        billingPointCode,
        paymentCode,
        null,
        billingPointCode + "|" + (paymentCode == null ? "" : paymentCode),
        "{}");
  }
}
