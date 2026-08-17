package com.threefees.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
    @NotBlank @Size(max = 72) String currentPassword,
    @NotBlank @Size(min = 6, max = 72) String newPassword,
    @NotBlank @Size(min = 6, max = 72) String confirmPassword) {}
