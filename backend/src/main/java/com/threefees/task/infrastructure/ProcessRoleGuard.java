package com.threefees.task.infrastructure;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ProcessRoleGuard implements ApplicationRunner {

  private static final Set<String> VALID_ROLES = Set.of("api", "worker", "all");

  private final String processRole;

  public ProcessRoleGuard(@Value("${three-fees.process-role:all}") String processRole) {
    this.processRole = processRole;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!VALID_ROLES.contains(processRole)) {
      throw new IllegalStateException("THREE_FEES_PROCESS_ROLE must be api, worker or all");
    }
  }
}
