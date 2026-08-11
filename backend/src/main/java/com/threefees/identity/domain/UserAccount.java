package com.threefees.identity.domain;

import java.time.LocalDateTime;
import java.util.Set;

public record UserAccount(
    long id,
    String username,
    String displayName,
    String passwordHash,
    String cityCode,
    String cityName,
    boolean enabled,
    boolean mustChangePassword,
    Set<Role> roles,
    LocalDateTime updatedAt,
    long version) {

  public UserAccount {
    roles = Set.copyOf(roles);
  }
}
