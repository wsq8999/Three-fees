package com.threefees.importing.application;

import com.threefees.file.application.StoredFileService;
import com.threefees.importing.domain.DatasetType;
import com.threefees.task.application.TaskExecutionException;
import com.threefees.task.application.TaskProcessor;
import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class ExportTaskProcessor implements TaskProcessor {

  private static final TypeReference<LinkedHashMap<String, String>> STRING_MAP =
      new TypeReference<>() {};
  private static final String MULTI_PERIOD_LABEL = "多账期";

  private final ExportJobRepository jobRepository;
  private final FieldCatalogService fieldCatalogService;
  private final StoredFileService storedFileService;
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public ExportTaskProcessor(
      ExportJobRepository jobRepository,
      FieldCatalogService fieldCatalogService,
      StoredFileService storedFileService,
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper) {
    this.jobRepository = jobRepository;
    this.fieldCatalogService = fieldCatalogService;
    this.storedFileService = storedFileService;
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public TaskType taskType() {
    return TaskType.EXPORT;
  }

  @Override
  public String process(BusinessTask task) {
    String jobId = payloadJobId(task.payloadJson());
    var job = jobRepository.findByPublicId(jobId).orElseThrow();
    jobRepository.markProcessing(job.id());
    try {
      List<SelectedBillingPoint> selectedPoints = selectedBillingPoints(job.billingPointIds());
      String periodLabel = exportPeriodLabel(selectedPoints, job.period());
      var files = new LinkedHashMap<String, byte[]>();
      for (DatasetType datasetType : job.datasetTypes()) {
        String filename = exportFileName(periodLabel, datasetType, selectedPoints.size(), "xlsx");
        files.put(filename, workbook(datasetType, selectedPoints));
      }
      byte[] resultBytes;
      String resultName;
      String mediaType;
      if (files.size() == 1) {
        var only = files.entrySet().iterator().next();
        resultName = only.getKey();
        resultBytes = only.getValue();
        mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      } else {
        resultName =
            exportBundleName(periodLabel, job.datasetTypes().size(), selectedPoints.size());
        resultBytes = zip(files);
        mediaType = "application/zip";
      }
      var stored =
          storedFileService.storeGenerated(
              resultBytes, resultName, mediaType, "EXPORT_RESULT", job.createdBy());
      jobRepository.markSucceeded(job.id(), stored.id());
      return writeJson(
          Map.of(
              "exportJobId",
              job.publicId(),
              "fileId",
              stored.publicId(),
              "downloadUrl",
              "/api/v1/files/" + stored.publicId()));
    } catch (RuntimeException exception) {
      jobRepository.markFailed(job.id(), "EXPORT_FAILED");
      throw new TaskExecutionException("EXPORT_FAILED", exception.getMessage(), true);
    }
  }

  private String exportFileName(
      String period, DatasetType datasetType, int billingPointCount, String extension) {
    return sanitizeFileName(
        periodLabel(period)
            + "电费稽核"
            + datasetTypeLabel(datasetType)
            + "导出"
            + scopeLabel(billingPointCount)
            + "."
            + extension);
  }

  private String exportBundleName(String period, int datasetTypeCount, int billingPointCount) {
    return sanitizeFileName(
        periodLabel(period)
            + "电费稽核多类数据导出"
            + "（共"
            + datasetTypeCount
            + "类）"
            + scopeLabel(billingPointCount)
            + ".zip");
  }

  private String datasetTypeLabel(DatasetType datasetType) {
    return switch (datasetType) {
      case BILLING_POINT -> "报账点清单";
      case PAYMENT -> "缴费明细";
      case METER_READING -> "电表读数";
      case BENCHMARK -> "标杆值";
    };
  }

  private String periodLabel(String period) {
    if (period != null && period.matches("\\d{4}-\\d{2}")) {
      return period.substring(0, 4) + "年" + period.substring(5, 7) + "月";
    }
    return period == null || period.isBlank() ? "" : period;
  }

  private String scopeLabel(int billingPointCount) {
    return billingPointCount <= 0 ? "（全部报账点）" : "（选中" + billingPointCount + "个报账点）";
  }

  private String sanitizeFileName(String value) {
    return value.replaceAll("[\\\\/:*?\"<>|]", "_");
  }

  private byte[] workbook(DatasetType datasetType, List<SelectedBillingPoint> selectedPoints) {
    try (var workbook = new SXSSFWorkbook(100);
        var output = new ByteArrayOutputStream()) {
      workbook.setCompressTempFiles(true);
      var sheet = workbook.createSheet("数据");
      CellStyle textStyle = workbook.createCellStyle();
      textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
      var header = sheet.createRow(0);
      var fields = fieldCatalogService.fields(datasetType);
      for (int index = 0; index < fields.size(); index++) {
        header.createCell(index).setCellValue(fields.get(index).sourceName());
      }
      List<Map<String, String>> rows = rows(datasetType, selectedPoints);
      for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
        var excelRow = sheet.createRow(rowIndex + 1);
        Map<String, String> values = rows.get(rowIndex);
        for (int column = 0; column < fields.size(); column++) {
          var cell = excelRow.createCell(column);
          cell.setCellStyle(textStyle);
          String value = values.getOrDefault(fields.get(column).technicalName(), "");
          cell.setCellValue(value.length() > 32767 ? value.substring(0, 32767) : value);
        }
      }
      workbook.write(output);
      workbook.dispose();
      return output.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Excel export could not be generated", exception);
    }
  }

  private List<Map<String, String>> rows(
      DatasetType datasetType, List<SelectedBillingPoint> selectedPoints) {
    if (selectedPoints.isEmpty()) {
      return List.of();
    }
    String placeholders =
        String.join(",", java.util.Collections.nCopies(selectedPoints.size(), "?"));
    Object[] publicIds = selectedPoints.stream().map(SelectedBillingPoint::publicId).toArray();
    List<String> jsonRows;
    if (datasetType == DatasetType.BILLING_POINT) {
      jsonRows =
          jdbcTemplate.queryForList(
              """
              SELECT COALESCE(m.resource_summary_json, selected.fallback_json)
                FROM (
                      SELECT city_code, billing_point_code, MIN(data_json) AS fallback_json
                        FROM billing_point_snapshot
                       WHERE public_id IN (
              """
                  + placeholders
                  + """
                       )
                       GROUP BY city_code, billing_point_code
                     ) selected
                LEFT JOIN billing_point_master m
                  ON m.city_code = selected.city_code
                 AND m.billing_point_code = selected.billing_point_code
               ORDER BY selected.city_code, selected.billing_point_code
              """,
              String.class,
              publicIds);
    } else {
      String tableName =
          switch (datasetType) {
            case PAYMENT -> "payment_detail";
            case METER_READING -> "meter_reading";
            case BENCHMARK -> "benchmark_value";
            case BILLING_POINT -> throw new IllegalStateException("Handled above");
          };
      jsonRows =
          jdbcTemplate.queryForList(
              """
              SELECT d.values_json
                FROM
              """
                  + tableName
                  + """
                   d
               WHERE EXISTS (
                     SELECT 1
                       FROM billing_point_snapshot s
                      WHERE s.public_id IN (
              """
                  + placeholders
                  + """
                      )
                        AND s.data_period = d.data_period
                        AND s.city_code = d.city_code
                        AND s.billing_point_code = d.billing_point_code
                   )
               ORDER BY d.data_period, d.city_code, d.billing_point_code, d.id
              """,
              String.class,
              publicIds);
    }
    return jsonRows.stream().map(this::readMap).map(value -> (Map<String, String>) value).toList();
  }

  private List<SelectedBillingPoint> selectedBillingPoints(List<String> publicIds) {
    String placeholders = String.join(",", java.util.Collections.nCopies(publicIds.size(), "?"));
    return jdbcTemplate.query(
        "SELECT public_id, data_period, city_code, billing_point_code FROM billing_point_snapshot WHERE public_id IN ("
            + placeholders
            + ") ORDER BY data_period, city_code, billing_point_code",
        (resultSet, rowNumber) ->
            new SelectedBillingPoint(
                resultSet.getString("public_id"),
                resultSet.getString("data_period"),
                resultSet.getString("city_code"),
                resultSet.getString("billing_point_code")),
        publicIds.toArray());
  }

  private String exportPeriodLabel(List<SelectedBillingPoint> selectedPoints, String fallback) {
    long periodCount = selectedPoints.stream().map(SelectedBillingPoint::period).distinct().count();
    if (periodCount > 1) {
      return MULTI_PERIOD_LABEL;
    }
    return selectedPoints.isEmpty() ? fallback : selectedPoints.get(0).period();
  }

  private byte[] zip(Map<String, byte[]> files) {
    try (var output = new ByteArrayOutputStream();
        var zip = new ZipOutputStream(output)) {
      for (var entry : files.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
      zip.finish();
      return output.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("ZIP export could not be generated", exception);
    }
  }

  private String payloadJobId(String json) {
    try {
      return objectMapper.readTree(json).path("exportJobId").asText();
    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted export task payload is invalid", exception);
    }
  }

  private LinkedHashMap<String, String> readMap(String json) {
    try {
      return objectMapper.readValue(json, STRING_MAP);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Persisted export row is invalid JSON", exception);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Export result could not be serialized", exception);
    }
  }

  private record SelectedBillingPoint(
      String publicId, String period, String cityCode, String billingPointCode) {}
}
