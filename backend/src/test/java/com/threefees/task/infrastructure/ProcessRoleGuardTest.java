package com.threefees.task.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class ProcessRoleGuardTest {

  @Test
  void invalidRoleIsRejected() {
    var guard = new ProcessRoleGuard("invalid");

    assertThatThrownBy(() -> guard.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("api, worker or all");
  }

  @Test
  void allRoleIsAccepted() {
    var guard = new ProcessRoleGuard("all");

    guard.run(new DefaultApplicationArguments());
  }
}
