package com.threefees.importing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.threefees.ThreeFeesApplication;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.domain.Role;
import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ImportBatchStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = ThreeFeesApplication.class)
class ImportCommandServiceIntegrationTest {

  private static final String INITIAL_TEST_PASSWORD = UUID.randomUUID().toString();

  @TempDir static java.nio.file.Path fileRoot;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("app.file.root", () -> fileRoot.toString());
    registry.add("app.bootstrap.enabled", () -> true);
    registry.add("app.bootstrap.initial-password", () -> INITIAL_TEST_PASSWORD);
  }

  @Autowired private ImportCommandService commandService;

  @Test
  void submitCreatesQueuedBatchAndTaskForCsvUpload() throws Exception {
    var upload =
        new MockMultipartFile(
            "file",
            "billing.csv",
            "text/csv",
            """
              报账点编码,报账点名称,所属地市
              E2E-BP-0001,端到端测试报账点,南京市
              """
                .getBytes(StandardCharsets.UTF_8));

    var batches =
        commandService.submit(
            DatasetType.BILLING_POINT,
            "2026-06",
            "320100",
            upload,
            UUID.randomUUID().toString(),
            administrator());
    var batch = batches.getFirst();

    assertThat(batch.status()).isEqualTo(ImportBatchStatus.QUEUED);
    assertThat(batch.taskPublicId()).isNotBlank();
    assertThat(Files.list(fileRoot))
        .anyMatch(path -> path.getFileName().toString().endsWith(".csv"));
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
