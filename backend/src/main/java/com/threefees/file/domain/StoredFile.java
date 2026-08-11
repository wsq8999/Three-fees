package com.threefees.file.domain;

import java.time.LocalDateTime;

public record StoredFile(
    long id,
    String publicId,
    String storageName,
    String originalName,
    String mediaType,
    long byteSize,
    String sha256,
    String purpose,
    LocalDateTime createdAt,
    String createdBy) {}
