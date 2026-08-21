package com.threefees.importing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.threefees.ThreeFeesApplication;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.domain.Role;
import com.threefees.importing.api.ImportBatchController;
import com.threefees.importing.api.ImportBatchResponse;
import com.threefees.importing.domain.DatasetType;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = ThreeFeesApplication.class)
class ImportSessionRecoveryIntegrationTest {

  private static final String CITY_CODE = "321200";
  private static final String ACTOR = "import-session-test";
  private static final String INITIAL_TEST_PASSWORD = UUID.randomUUID().toString();

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ImportBatchController controller;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("app.bootstrap.enabled", () -> true);
    registry.add("app.bootstrap.initial-password", () -> INITIAL_TEST_PASSWORD);
  }

  @BeforeEach
  void clean() {
    jdbcTemplate.update("DELETE FROM import_job WHERE created_by=?", ACTOR);
    jdbcTemplate.update("DELETE FROM stored_file WHERE created_by=?", ACTOR);
  }

  @Test
  void latestSessionDoesNotMixOlderBusinessFilesAfterBillingPointReimport() {
    var firstRound = LocalDateTime.of(2026, 8, 20, 10, 0);
    insertBatch(DatasetType.BILLING_POINT, "MASTER", "ACTIVE", firstRound, file("old-billing"));
    insertBatch(DatasetType.PAYMENT, "2026-06", "ACTIVE", firstRound.plusMinutes(1), file("old-payment"));
    insertBatch(
        DatasetType.METER_READING,
        "2026-06",
        "ACTIVE",
        firstRound.plusMinutes(2),
        file("old-meter"));
    insertBatch(
        DatasetType.BENCHMARK, "2026-06", "ACTIVE", firstRound.plusMinutes(3), file("old-benchmark"));

    var secondRound = LocalDateTime.of(2026, 8, 20, 10, 30);
    insertBatch(DatasetType.BILLING_POINT, "MASTER", "ACTIVE", secondRound, file("new-billing"));

    var response = controller.latestSession(CITY_CODE, administrator());

    assertThat(response.sessionAnchorType()).isEqualTo(DatasetType.BILLING_POINT.name());
    assertThat(response.sessionStartedAt()).isEqualTo(secondRound);
    assertThat(status(response, DatasetType.BILLING_POINT)).isEqualTo("SUCCESS");
    assertThat(status(response, DatasetType.PAYMENT)).isEqualTo("NOT_STARTED");
    assertThat(status(response, DatasetType.METER_READING)).isEqualTo("NOT_STARTED");
    assertThat(status(response, DatasetType.BENCHMARK)).isEqualTo("NOT_STARTED");
    assertThat(response.allCompleted()).isFalse();
  }

  @Test
  void latestSessionIncludesBusinessFilesCreatedAfterLatestBillingPointAnchor() {
    var firstRound = LocalDateTime.of(2026, 8, 20, 10, 0);
    insertBatch(DatasetType.BILLING_POINT, "MASTER", "ACTIVE", firstRound, file("old-billing"));
    insertBatch(DatasetType.PAYMENT, "2026-05", "ACTIVE", firstRound.plusMinutes(1), file("old-payment"));

    var secondRound = LocalDateTime.of(2026, 8, 20, 10, 30);
    insertBatch(DatasetType.BILLING_POINT, "MASTER", "ACTIVE", secondRound, file("new-billing"));
    insertBatch(
        DatasetType.PAYMENT, "2026-06", "ACTIVE", secondRound.plusMinutes(1), file("new-payment"));

    var response = controller.latestSession(CITY_CODE, administrator());

    assertThat(status(response, DatasetType.BILLING_POINT)).isEqualTo("SUCCESS");
    assertThat(status(response, DatasetType.PAYMENT)).isEqualTo("SUCCESS");
    assertThat(
            response.items().stream()
                .filter(item -> item.datasetType() == DatasetType.PAYMENT)
                .findFirst()
                .orElseThrow()
                .batches())
        .extracting(ImportBatchResponse::period)
        .containsExactly("2026-06");
    assertThat(status(response, DatasetType.METER_READING)).isEqualTo("NOT_STARTED");
    assertThat(status(response, DatasetType.BENCHMARK)).isEqualTo("NOT_STARTED");
  }

  @Test
  void latestSessionReturnsNotStartedWhenBillingPointWasNeverImported() {
    insertBatch(
        DatasetType.PAYMENT,
        "2026-06",
        "ACTIVE",
        LocalDateTime.of(2026, 8, 20, 10, 0),
        file("orphan-payment"));

    var response = controller.latestSession(CITY_CODE, administrator());

    assertThat(response.sessionStartedAt()).isNull();
    assertThat(response.sessionAnchorType()).isNull();
    assertThat(response.items()).allMatch(item -> "NOT_STARTED".equals(item.status()));
    assertThat(response.allCompleted()).isFalse();
  }

  private String status(ImportBatchController.ImportSessionResponse response, DatasetType type) {
    return response.items().stream()
        .filter(item -> item.datasetType() == type)
        .findFirst()
        .orElseThrow()
        .status();
  }

  private long file(String name) {
    String publicId = UUID.randomUUID().toString();
    jdbcTemplate.update(
        """
        INSERT INTO stored_file
          (public_id, storage_name, original_name, media_type, file_ext, byte_size,
           sha256, purpose, created_by)
        VALUES (?, ?, ?, 'text/csv', 'csv', 1,
                '0000000000000000000000000000000000000000000000000000000000000000',
                'IMPORT', ?)
        """,
        publicId,
        publicId + ".csv",
        name + ".csv",
        ACTOR);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM stored_file WHERE public_id=?", Long.class, publicId);
  }

  private void insertBatch(
      DatasetType datasetType,
      String period,
      String status,
      LocalDateTime createdAt,
      long sourceFileId) {
    jdbcTemplate.update(
        """
        INSERT INTO import_job
          (public_id, dataset_type, data_period, city_code, status, source_file_id,
           task_public_id, row_count, error_count, errors_json, completed_at,
           created_at, created_by, updated_at, updated_by)
        VALUES (?, ?, ?, ?, ?, ?, ?, 1, 0, '[]', ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID().toString(),
        datasetType.name(),
        period,
        CITY_CODE,
        status,
        sourceFileId,
        UUID.randomUUID().toString(),
        createdAt,
        createdAt,
        ACTOR,
        createdAt,
        ACTOR);
  }

  private CurrentUser administrator() {
    return new CurrentUser() {
      @Override
      public long id() {
        return 1L;
      }

      @Override
      public String username() {
        return "admin";
      }

      @Override
      public String displayName() {
        return "超级管理员";
      }

      @Override
      public String cityCode() {
        return "";
      }

      @Override
      public String cityName() {
        return "";
      }

      @Override
      public boolean mustChangePassword() {
        return false;
      }

      @Override
      public Set<Role> roles() {
        return Set.of(Role.SUPER_ADMIN);
      }
    };
  }
}
