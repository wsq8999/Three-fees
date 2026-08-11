package com.threefees.importing.infrastructure.persistence;

import com.threefees.importing.application.ExportJobRepository;
import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ExportJob;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcExportJobRepository implements ExportJobRepository {

  private static final TypeReference<List<DatasetType>> DATASET_TYPES = new TypeReference<>() {};
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public JdbcExportJobRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public ExportJob create(
      String publicId,
      String period,
      String cityCode,
      List<DatasetType> datasetTypes,
      List<String> billingPointIds,
      String taskPublicId,
      String actor) {
    var keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(
        connection -> {
          PreparedStatement statement =
              connection.prepareStatement(
                  """
                  INSERT INTO export_job
                    (public_id, data_period, city_code, dataset_types_json,
                     billing_point_ids_json, task_public_id, status, created_by, updated_by)
                  VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)
                  """,
                  new String[] {"id"});
          statement.setString(1, publicId);
          statement.setString(2, period);
          statement.setString(3, cityCode);
          statement.setString(4, writeJson(datasetTypes));
          statement.setString(5, writeJson(billingPointIds));
          statement.setString(6, taskPublicId);
          statement.setString(7, actor);
          statement.setString(8, actor);
          return statement;
        },
        keyHolder);
    return findByPublicId(publicId).orElseThrow();
  }

  @Override
  public Optional<ExportJob> findByPublicId(String publicId) {
    return jdbcTemplate
        .query(
            """
            SELECT id, public_id, data_period, city_code, dataset_types_json,
                   billing_point_ids_json, task_public_id, status, result_file_id,
                   error_code, created_at, created_by, updated_at
              FROM export_job WHERE public_id = ?
            """,
            (resultSet, rowNumber) ->
                new ExportJob(
                    resultSet.getLong("id"),
                    resultSet.getString("public_id"),
                    resultSet.getString("data_period"),
                    resultSet.getString("city_code"),
                    readJson(resultSet.getString("dataset_types_json"), DATASET_TYPES),
                    readJson(resultSet.getString("billing_point_ids_json"), STRING_LIST),
                    resultSet.getString("task_public_id"),
                    resultSet.getString("status"),
                    resultSet.getObject("result_file_id", Long.class),
                    resultSet.getString("error_code"),
                    resultSet.getObject("created_at", LocalDateTime.class),
                    resultSet.getString("created_by"),
                    resultSet.getObject("updated_at", LocalDateTime.class)),
            publicId)
        .stream()
        .findFirst();
  }

  @Override
  public void markProcessing(long id) {
    jdbcTemplate.update(
        """
        UPDATE export_job SET status = 'PROCESSING', error_code = NULL,
               updated_at = CURRENT_TIMESTAMP(3), updated_by = 'WORKER', version = version + 1
         WHERE id = ? AND status IN ('QUEUED', 'FAILED')
        """,
        id);
  }

  @Override
  public void markSucceeded(long id, long resultFileId) {
    jdbcTemplate.update(
        """
        UPDATE export_job SET status = 'SUCCEEDED', result_file_id = ?, error_code = NULL,
               updated_at = CURRENT_TIMESTAMP(3), updated_by = 'WORKER', version = version + 1
         WHERE id = ?
        """,
        resultFileId,
        id);
  }

  @Override
  public void markFailed(long id, String errorCode) {
    jdbcTemplate.update(
        """
        UPDATE export_job SET status = 'FAILED', error_code = ?,
               updated_at = CURRENT_TIMESTAMP(3), updated_by = 'WORKER', version = version + 1
         WHERE id = ?
        """,
        errorCode,
        id);
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Export job could not be serialized", exception);
    }
  }

  private <T> T readJson(String json, TypeReference<T> type) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted export job is invalid JSON", exception);
    }
  }
}
