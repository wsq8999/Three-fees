package com.threefees.identity.infrastructure.persistence;

import java.time.LocalDateTime;

public record UserRow(
    long id,
    String username,
    String displayName,
    String passwordHash,
    String cityCode,
    String cityName,
    boolean enabled,
    boolean mustChangePassword,
    LocalDateTime updatedAt,
    long version) {}
