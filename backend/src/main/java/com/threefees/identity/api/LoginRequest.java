package com.threefees.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record LoginRequest(
    @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_]+", message = "用户名格式不正确")
        String username,
    @NotBlank @Size(max = 72) String password) {}
