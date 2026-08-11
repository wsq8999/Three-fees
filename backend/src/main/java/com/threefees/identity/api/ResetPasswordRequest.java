package com.threefees.identity.api;

import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(@Size(min = 6, max = 72) String newPassword) {}
