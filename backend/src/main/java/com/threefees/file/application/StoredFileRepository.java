package com.threefees.file.application;

import com.threefees.file.domain.StoredFile;
import java.util.Optional;

public interface StoredFileRepository {

  StoredFile create(
      String publicId,
      String storageName,
      String originalName,
      String mediaType,
      long byteSize,
      String sha256,
      String purpose,
      String createdBy);

  Optional<StoredFile> findByPublicId(String publicId);

  Optional<StoredFile> findById(long id);

  boolean deleteById(long id);
}
