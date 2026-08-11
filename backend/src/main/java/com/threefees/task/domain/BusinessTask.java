package com.threefees.task.domain;

import java.time.LocalDateTime;

public record BusinessTask(
    long id,
    String publicId,
    TaskType type,
    String businessKey,
    TaskStatus status,
    int attempts,
    int maxAttempts,
    LocalDateTime nextRunAt,
    String leaseOwner,
    LocalDateTime leaseExpiresAt,
    String payloadJson,
    String resultJson,
    String errorCode,
    LocalDateTime createdAt,
    String createdBy,
    LocalDateTime updatedAt,
    long version) {}
