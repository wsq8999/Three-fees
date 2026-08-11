package com.threefees;

import static org.assertj.core.api.Assertions.assertThat;

import com.threefees.identity.infrastructure.bootstrap.InitialAccountBootstrapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class WorkerModeContextTest {

  private static final String WORKER_DATA_SOURCE_URL =
      "jdbc:h2:mem:worker_mode;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
          + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

  @Test
  void workerRoleStartsWithoutWebOrAccountBootstrap() {
    try (var context =
        new SpringApplicationBuilder(ThreeFeesApplication.class)
            .web(WebApplicationType.NONE)
            .properties(
                "three-fees.process-role=worker",
                "app.bootstrap.enabled=true",
                "spring.datasource.url=" + WORKER_DATA_SOURCE_URL,
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver")
            .run()) {
      assertThat(context.getEnvironment().getProperty("local.server.port")).isNull();
      assertThat(context.getBeansOfType(InitialAccountBootstrapper.class)).isEmpty();
    }
  }
}
