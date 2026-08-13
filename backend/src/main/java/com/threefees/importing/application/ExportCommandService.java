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
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
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
    validatePeriod(period);
    String cityCode = cityScope(actor, requestedCityCode);
    List<DatasetType> types = List.copyOf(new LinkedHashSet<>(requestedTypes));
    if (types.isEmpty()) {
      throw new BusinessRuleException("EXPORT_TYPE_REQUIRED", "至少选择一种导出数据类型");
    }
    if (billingPointIds == null || billingPointIds.isEmpty()) {
      throw new BusinessRuleException("EXPORT_SELECTION_REQUIRED", "请至少选择一个报账点");
    }
    validateBillingPointScope(period, cityCode, billingPointIds);
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
        jobId, period, cityCode, types, billingPointIds, task.publicId(), actor.username());
  }

  private void validateBillingPointScope(
      String period, String cityCode, List<String> billingPointIds) {
    String placeholders =
        String.join(",", java.util.Collections.nCopies(billingPointIds.size(), "?"));
    var arguments = new java.util.ArrayList<Object>();
    arguments.add(period);
    arguments.add(cityCode);
    arguments.addAll(billingPointIds);
    Integer count =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM billing_point_snapshot s
             WHERE s.data_period = ? AND s.city_code = ? AND s.public_id IN (
            """
                + placeholders
                + ")",
            Integer.class,
            arguments.toArray());
    if (count == null || count != new LinkedHashSet<>(billingPointIds).size()) {
      throw new AccessDeniedException("Export selection is outside city scope or not active");
    }
  }

  private String cityScope(CurrentUser actor, String requestedCityCode) {
    if (!actor.roles().contains(Role.SUPER_ADMIN)) {
      if (requestedCityCode != null
          && !requestedCityCode.isBlank()
          && !requestedCityCode.equals(actor.cityCode())) {
        throw new AccessDeniedException("City scope mismatch");
      }
      return actor.cityCode();
    }
    if (requestedCityCode == null || requestedCityCode.isBlank()) {
      throw new BusinessRuleException("CITY_REQUIRED", "超级管理员导出时必须选择地市");
    }
    return requestedCityCode;
  }

  private void validatePeriod(String period) {
    try {
      YearMonth.parse(period);
    } catch (DateTimeParseException exception) {
      throw new BusinessRuleException("PERIOD_INVALID", "账期必须使用 YYYY-MM 格式");
    }
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
}
