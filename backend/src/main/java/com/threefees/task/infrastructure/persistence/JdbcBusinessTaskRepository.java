package com.threefees.task.infrastructure.persistence;

import com.threefees.task.application.BusinessTaskRepository;
import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskStatus;
import com.threefees.task.domain.TaskType;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcBusinessTaskRepository implements BusinessTaskRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcBusinessTaskRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public BusinessTask create(
      TaskType taskType, String businessKey, String payloadJson, String actor, int maxAttempts) {
    String publicId = UUID.randomUUID().toString();
    var keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(
        connection -> {
          PreparedStatement statement =
              connection.prepareStatement(
                  """
                  INSERT INTO business_task
                    (public_id, task_type, business_key, status, attempts, max_attempts,
                     next_run_at, payload_json, created_by, updated_by)
                  VALUES (?, ?, ?, 'QUEUED', 0, ?, CURRENT_TIMESTAMP(3), ?, ?, ?)
                  """,
                  new String[] {"id"});
          statement.setString(1, publicId);
          statement.setString(2, taskType.name());
          statement.setString(3, businessKey);
          statement.setInt(4, maxAttempts);
          statement.setString(5, payloadJson);
          statement.setString(6, actor);
          statement.setString(7, actor);
          return statement;
        },
        keyHolder);
    return findByPublicId(publicId).orElseThrow();
  }

  @Override
  public Optional<BusinessTask> findByPublicId(String publicId) {
    return query("WHERE public_id = ?", publicId).stream().findFirst();
  }

  @Override
  public Optional<BusinessTask> findByTypeAndBusinessKey(TaskType taskType, String businessKey) {
    return query("WHERE task_type = ? AND business_key = ?", taskType.name(), businessKey).stream()
        .findFirst();
  }

  @Override
  @Transactional
  public List<BusinessTask> claimAvailable(String leaseOwner, Duration leaseDuration, int limit) {
    jdbcTemplate.update(
        """
        UPDATE business_task
           SET status = 'FAILED', error_code = 'TASK_LEASE_EXPIRED',
               lease_owner = NULL, lease_expires_at = NULL,
               updated_at = CURRENT_TIMESTAMP(3), updated_by = 'LEASE_RECOVERY',
               version = version + 1
         WHERE status = 'RUNNING' AND lease_expires_at < CURRENT_TIMESTAMP(3)
           AND attempts >= max_attempts
        """);
    List<BusinessTask> candidates =
        jdbcTemplate.query(
            """
            SELECT id, public_id, task_type, business_key, status, attempts, max_attempts,
                   next_run_at, lease_owner, lease_expires_at, payload_json, result_json,
                   error_code, created_at, created_by, updated_at, version
              FROM business_task
             WHERE attempts < max_attempts
               AND (
                    (status IN ('QUEUED', 'RETRY_WAIT')
                     AND (next_run_at IS NULL OR next_run_at <= CURRENT_TIMESTAMP(3))
                     AND (lease_expires_at IS NULL
                          OR lease_expires_at < CURRENT_TIMESTAMP(3)))
                    OR (status = 'RUNNING' AND lease_expires_at < CURRENT_TIMESTAMP(3))
               )
             ORDER BY id
             LIMIT ?
            """,
            this::map,
            limit);
    var claimed = new ArrayList<BusinessTask>();
    for (BusinessTask candidate : candidates) {
      int updated =
          jdbcTemplate.update(
              """
              UPDATE business_task
                 SET status = 'RUNNING', attempts = attempts + 1,
                     lease_owner = ?,
                     lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND),
                     updated_at = CURRENT_TIMESTAMP(3), updated_by = ?, version = version + 1
               WHERE id = ? AND version = ?
                 AND attempts < max_attempts
                 AND (
                      (status IN ('QUEUED', 'RETRY_WAIT')
                       AND (lease_expires_at IS NULL
                            OR lease_expires_at < CURRENT_TIMESTAMP(3)))
                      OR (status = 'RUNNING' AND lease_expires_at < CURRENT_TIMESTAMP(3))
                 )
              """,
              leaseOwner,
              leaseDuration.toSeconds(),
              leaseOwner,
              candidate.id(),
              candidate.version());
      if (updated == 1) {
        findByPublicId(candidate.publicId()).ifPresent(claimed::add);
      }
    }
    return List.copyOf(claimed);
  }

  @Override
  public boolean renewLease(BusinessTask task, Duration leaseDuration) {
    return jdbcTemplate.update(
            """
            UPDATE business_task
               SET lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND),
                   updated_at = CURRENT_TIMESTAMP(3),
                   updated_by = ?, version = version + 1
             WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
            """,
            leaseDuration.toSeconds(),
            task.leaseOwner(),
            task.id(),
            task.leaseOwner())
        == 1;
  }

  @Override
  public boolean succeed(BusinessTask task, String resultJson) {
    return jdbcTemplate.update(
            """
        UPDATE business_task
           SET status = 'SUCCEEDED', result_json = ?, error_code = NULL,
               lease_owner = NULL, lease_expires_at = NULL, next_run_at = NULL,
               updated_at = CURRENT_TIMESTAMP(3), updated_by = ?, version = version + 1
         WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
        """,
            resultJson,
            task.leaseOwner(),
            task.id(),
            task.leaseOwner())
        == 1;
  }

  @Override
  public boolean fail(BusinessTask task, String errorCode, boolean retryable, Duration retryDelay) {
    boolean shouldRetry = retryable && task.attempts() < task.maxAttempts();
    return jdbcTemplate.update(
            """
        UPDATE business_task
           SET status = ?, error_code = ?,
               next_run_at = CASE
                 WHEN ? THEN DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND)
                 ELSE NULL
               END,
               lease_owner = NULL, lease_expires_at = NULL,
               updated_at = CURRENT_TIMESTAMP(3), updated_by = ?, version = version + 1
         WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
        """,
            shouldRetry ? TaskStatus.RETRY_WAIT.name() : TaskStatus.FAILED.name(),
            errorCode,
            shouldRetry,
            retryDelay.toSeconds(),
            task.leaseOwner(),
            task.id(),
            task.leaseOwner())
        == 1;
  }

  @Override
  public boolean retry(String publicId) {
    return jdbcTemplate.update(
            """
            UPDATE business_task
               SET status = 'QUEUED', attempts = 0, next_run_at = CURRENT_TIMESTAMP(3),
                   lease_owner = NULL, lease_expires_at = NULL, error_code = NULL,
                   updated_at = CURRENT_TIMESTAMP(3), updated_by = 'MANUAL_RETRY',
                   version = version + 1
             WHERE public_id = ? AND status = 'FAILED'
            """,
            publicId)
        == 1;
  }

  @Override
  public boolean requeueWithPayload(String publicId, String payloadJson, String actor) {
    return jdbcTemplate.update(
            """
            UPDATE business_task
               SET status = 'QUEUED',
                   attempts = 0,
                   next_run_at = CURRENT_TIMESTAMP(3),
                   lease_owner = NULL,
                   lease_expires_at = NULL,
                   payload_json = ?,
                   result_json = NULL,
                   error_code = NULL,
                   updated_at = CURRENT_TIMESTAMP(3),
                   updated_by = ?,
                   version = version + 1
             WHERE public_id = ?
               AND status IN ('FAILED', 'SUCCEEDED')
            """,
            payloadJson,
            actor,
            publicId)
        == 1;
  }

  private List<BusinessTask> query(String predicate, Object... arguments) {
    return jdbcTemplate.query(
        """
        SELECT id, public_id, task_type, business_key, status, attempts, max_attempts,
               next_run_at, lease_owner, lease_expires_at, payload_json, result_json,
               error_code, created_at, created_by, updated_at, version
          FROM business_task
        """
            + predicate,
        this::map,
        arguments);
  }

  private BusinessTask map(java.sql.ResultSet resultSet, int rowNumber)
      throws java.sql.SQLException {
    return new BusinessTask(
        resultSet.getLong("id"),
        resultSet.getString("public_id"),
        TaskType.valueOf(resultSet.getString("task_type")),
        resultSet.getString("business_key"),
        TaskStatus.valueOf(resultSet.getString("status")),
        resultSet.getInt("attempts"),
        resultSet.getInt("max_attempts"),
        resultSet.getObject("next_run_at", LocalDateTime.class),
        resultSet.getString("lease_owner"),
        resultSet.getObject("lease_expires_at", LocalDateTime.class),
        resultSet.getString("payload_json"),
        resultSet.getString("result_json"),
        resultSet.getString("error_code"),
        resultSet.getObject("created_at", LocalDateTime.class),
        resultSet.getString("created_by"),
        resultSet.getObject("updated_at", LocalDateTime.class),
        resultSet.getLong("version"));
  }
}
