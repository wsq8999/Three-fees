package com.threefees.task.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class ProcessRoleGuardTest {

  @Test
  void allRoleIsRejectedWithoutAnIsolatedProfile() {
    var guard = new ProcessRoleGuard("all", new MockEnvironment().withProperty("x", "y"));

    assertThatThrownBy(() -> guard.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("restricted");
  }

  @Test
  void allRoleIsAcceptedForDev() {
    var environment = new MockEnvironment();
    environment.setActiveProfiles("dev");
    var guard = new ProcessRoleGuard("all", environment);

    guard.run(new DefaultApplicationArguments());
  }
}
