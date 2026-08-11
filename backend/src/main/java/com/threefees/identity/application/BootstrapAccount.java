package com.threefees.identity.application;

import com.threefees.identity.domain.Role;
import java.util.Set;

public record BootstrapAccount(
    String username, String displayName, String cityCode, Set<Role> roles) {

  public BootstrapAccount {
    roles = Set.copyOf(roles);
  }
}
