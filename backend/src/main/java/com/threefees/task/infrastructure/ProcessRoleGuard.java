package com.threefees.task.infrastructure;

import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProcessRoleGuard implements ApplicationRunner {

  private static final Set<String> VALID_ROLES = Set.of("api", "worker", "all");
  private static final Set<String> ALL_ROLE_PROFILES = Set.of("dev", "e2e", "test");

  private final String processRole;
  private final Environment environment;

  public ProcessRoleGuard(
      @Value("${three-fees.process-role:api}") String processRole, Environment environment) {
    this.processRole = processRole;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!VALID_ROLES.contains(processRole)) {
      throw new IllegalStateException("THREE_FEES_PROCESS_ROLE must be api, worker or all");
    }
    if ("all".equals(processRole)
        && Arrays.stream(environment.getActiveProfiles()).noneMatch(ALL_ROLE_PROFILES::contains)) {
      throw new IllegalStateException(
          "THREE_FEES_PROCESS_ROLE=all is restricted to dev/e2e/test profiles");
    }
  }
}
