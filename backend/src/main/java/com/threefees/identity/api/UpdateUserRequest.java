package com.threefees.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
    @NotBlank @Size(max = 64) String displayName,
    @NotBlank @Pattern(regexp = "32[0-9]{4}") String cityCode,
    boolean enabled,
    long version) {}
