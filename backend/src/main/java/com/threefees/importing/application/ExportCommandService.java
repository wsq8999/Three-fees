package com.threefees.importing.application;

import com.threefees.identity.application.BusinessRuleException;
import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.domain.Role;
import com.threefees.importing.domain.DatasetType;
import com.threefees.importing.domain.ExportJob;
import com.threefees.task.application.BusinessTaskRepository;
import com.threefees.task.domain.TaskType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ExportCommandService {

  private final ExportJobRepository jobRepository;
  private final BusinessTaskRepository taskRepository;
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public ExportCommandService(
      ExportJobRepository jobRepository,
      BusinessTaskRepository taskRepository,
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper) {
    this.jobRepository = jobRepository;
    this.taskRepository = taskRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public ExportJob submit(
      String period,
      String requestedCityCode,
      List<DatasetType> requestedTypes,
      List<String> billingPointIds,
      String idempotencyKey,
      CurrentUser actor) {
    List<DatasetType> types = List.copyOf(new LinkedHashSet<>(requestedTypes));
    if (types.isEmpty()) {
      throw new BusinessRuleException("EXPORT_TYPE_REQUIRED", "至少选择一种导出数据类型");
    }
    if (billingPointIds == null || billingPointIds.isEmpty()) {
      throw new BusinessRuleException("EXPORT_SELECTION_REQUIRED", "请至少选择一个报账点");
    }
    List<SelectedBillingPoint> selectedPoints = selectedBillingPoints(billingPointIds);
    validateBillingPointScope(selectedPoints, billingPointIds, actor);
    SelectedBillingPoint firstPoint = selectedPoints.get(0);
    String cityCode = firstPoint.cityCode();
    String jobId = UUID.randomUUID().toString();
    String normalizedKey =
        idempotencyKey == null || idempotencyKey.isBlank()
            ? UUID.randomUUID().toString()
            : idempotencyKey;
    String businessKey = "EXPORT:" + digest(normalizedKey + actor.username());
    var task =
        taskRepository.create(
            TaskType.EXPORT,
            businessKey,
            writeJson(Map.of("exportJobId", jobId)),
            actor.username(),
            3);
    return jobRepository.create(
        jobId,
        firstPoint.period(),
        cityCode,
        types,
        billingPointIds,
        task.publicId(),
        actor.username());
  }

  private List<SelectedBillingPoint> selectedBillingPoints(List<String> billingPointIds) {
    String placeholders =
        String.join(",", java.util.Collections.nCopies(billingPointIds.size(), "?"));
    return jdbcTemplate.query(
            """
            SELECT public_id, data_period, city_code
              FROM billing_point_snapshot
             WHERE public_id IN (
            """
                + placeholders
                + ") ORDER BY data_period, city_code, billing_point_code",
            this::mapSelectedBillingPoint,
            billingPointIds.toArray());
  }

  private void validateBillingPointScope(
      List<SelectedBillingPoint> selectedPoints,
      List<String> billingPointIds,
      CurrentUser actor) {
    int selectedCount = new LinkedHashSet<>(billingPointIds).size();
    if (selectedPoints.size() != selectedCount) {
      throw new AccessDeniedException("Export selection contains unavailable billing points");
    }
    if (!actor.roles().contains(Role.SUPER_ADMIN)
        && selectedPoints.stream().anyMatch(point -> !point.cityCode().equals(actor.cityCode()))) {
      throw new AccessDeniedException("Export selection is outside city scope");
    }
  }

  private SelectedBillingPoint mapSelectedBillingPoint(ResultSet resultSet, int rowNumber)
      throws SQLException {
    return new SelectedBillingPoint(
        resultSet.getString("public_id"),
        resultSet.getString("data_period"),
        resultSet.getString("city_code"));
  }

  private String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
          .substring(0, 32);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Export task payload could not be serialized", exception);
    }
  }

  private record SelectedBillingPoint(String publicId, String period, String cityCode) {}
}
