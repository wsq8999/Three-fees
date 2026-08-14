package com.threefees.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
    @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_]+") String username,
    @NotBlank @Size(max = 64) String displayName,
    @NotBlank @Pattern(regexp = "32[0-9]{4}") String cityCode,
    boolean enabled,
    @NotBlank @Size(min = 6, max = 72) String initialPassword,
    @NotBlank @Size(min = 6, max = 72) String confirmPassword) {}