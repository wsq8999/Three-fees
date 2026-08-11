package com.threefees.file.infrastructure.persistence;

import com.threefees.file.application.StoredFileRepository;
import com.threefees.file.domain.StoredFile;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStoredFileRepository implements StoredFileRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcStoredFileRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public StoredFile create(
      String publicId,
      String storageName,
      String originalName,
      String mediaType,
      long byteSize,
      String sha256,
      String purpose,
      String createdBy) {
    var keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(
        connection -> {
          PreparedStatement statement =
              connection.prepareStatement(
                  """
                  INSERT INTO stored_file
                    (public_id, storage_name, original_name, media_type, byte_size, sha256,
                     purpose, created_by)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                  """,
                  new String[] {"id"});
          statement.setString(1, publicId);
          statement.setString(2, storageName);
          statement.setString(3, originalName);
          statement.setString(4, mediaType);
          statement.setLong(5, byteSize);
          statement.setString(6, sha256);
          statement.setString(7, purpose);
          statement.setString(8, createdBy);
          return statement;
        },
        keyHolder);
    Number key = keyHolder.getKey();
    if (key == null) {
      throw new IllegalStateException("Stored file key was not generated");
    }
    return findById(key.longValue()).orElseThrow();
  }

  @Override
  public Optional<StoredFile> findByPublicId(String publicId) {
    return queryOne("WHERE public_id = ?", publicId);
  }

  @Override
  public Optional<StoredFile> findById(long id) {
    return queryOne("WHERE id = ?", id);
  }

  @Override
  public boolean deleteById(long id) {
    return jdbcTemplate.update("DELETE FROM stored_file WHERE id = ?", id) == 1;
  }

  private Optional<StoredFile> queryOne(String predicate, Object value) {
    return jdbcTemplate
        .query(
            """
            SELECT id, public_id, storage_name, original_name, media_type, byte_size,
                   sha256, purpose, created_at, created_by
              FROM stored_file
            """
                + predicate,
            (resultSet, rowNumber) ->
                new StoredFile(
                    resultSet.getLong("id"),
                    resultSet.getString("public_id"),
                    resultSet.getString("storage_name"),
                    resultSet.getString("original_name"),
                    resultSet.getString("media_type"),
                    resultSet.getLong("byte_size"),
                    resultSet.getString("sha256"),
                    resultSet.getString("purpose"),
                    resultSet.getObject("created_at", LocalDateTime.class),
                    resultSet.getString("created_by")),
            value)
        .stream()
        .findFirst();
  }
}
