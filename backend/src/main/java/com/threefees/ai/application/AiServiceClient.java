package com.threefees.ai.application;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class AiServiceClient {

  private final URI baseUri;
  private final String token;
  private final Duration timeout;
  private final HttpClient httpClient;

  @Autowired private ObjectMapper objectMapper;

  public AiServiceClient(
      @Value("${app.ai.base-url:http://127.0.0.1:8100}") String baseUrl,
      @Value("${app.ai.token:}") String token,
      @Value("${app.ai.timeout-seconds:30}") long timeoutSeconds) {
    this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
    this.token = token;
    this.timeout = Duration.ofSeconds(timeoutSeconds);
    this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  public ReportImageAnalysisResult analyzeReportImages(
      String billingPointCode,
      String period,
      String contentHtml,
      String instruction,
      List<Fact> facts,
      List<AiImage> images,
      String traceId) {
    String taskId = billingPointCode + "-" + period + "-image-analysis";
    Map<String, Object> request =
        Map.of(
            "metadata", metadata(taskId, (instruction == null ? "" : instruction) + contentHtml, traceId),
            "billing_point_code", billingPointCode,
            "period", period,
            "content_html", contentHtml,
            "instruction", instruction == null ? "" : instruction,
            "facts",
                facts.stream()
                    .map(fact -> Map.of("field_name", fact.fieldName(), "value", fact.value()))
                    .toList(),
            "images",
                images.stream()
                    .map(
                        image ->
                            Map.of(
                                "file_name", image.fileName(),
                                "media_type", image.mediaType(),
                                "base64_data",
                                    Base64.getEncoder().encodeToString(image.bytes())))
                    .toList());
    JsonNode response = post("api/v1/report-image-analysis", request, traceId);
    return new ReportImageAnalysisResult(
        response.path("answer").asText(""),
        response.path("updated_content_html").asText(""),
        response.path("analysis_text").asText(""));
  }

  public AssistanceResult assist(
      String taskId,
      String intent,
      String instruction,
      ReportSections sections,
      List<Fact> facts,
      List<AiImage> images,
      String traceId) {
    throw new AiServiceException(
        "AI_DRAFT_ASSIST_DISABLED",
        "旧草稿 AI 辅助入口已停用，请在生成报告页面使用“分析图片”。",
        false);
  }

  private JsonNode post(String path, Object requestBody, String traceId) {
    if (token == null || token.length() < 16) {
      throw new AiServiceException("AI_SERVICE_NOT_CONFIGURED", "AI 服务令牌未配置或长度不足", false);
    }
    try {
      String body = objectMapper.writeValueAsString(requestBody);
      HttpRequest request =
          HttpRequest.newBuilder(baseUri.resolve(path))
              .timeout(timeout)
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .header("X-Trace-Id", traceId)
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() == 429) {
        throw new AiServiceException("AI_RATE_LIMITED", "AI 服务限流，请稍后再试", true);
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new AiServiceException(
            "AI_SERVICE_ERROR",
            "AI 服务调用失败，HTTP " + response.statusCode(),
            response.statusCode() >= 500);
      }
      JsonNode json = objectMapper.readTree(response.body());
      String responseJobId =
          json.path("metadata").path("jobId").asText(json.path("metadata").path("job_id").asText());
      if (!responseJobId.isBlank() && !responseJobId.equals(extractJobId(requestBody))) {
        throw new AiServiceException("AI_RESPONSE_MISMATCH", "AI 响应任务标识不一致", false);
      }
      return json;
    } catch (java.net.http.HttpTimeoutException exception) {
      throw new AiServiceException("AI_TIMEOUT", "AI 服务调用超时", true);
    } catch (IOException exception) {
      throw new AiServiceException("AI_NETWORK_ERROR", "AI 服务网络调用失败", true);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AiServiceException("AI_INTERRUPTED", "AI 服务调用被中断", true);
    }
  }

  private Map<String, String> metadata(String taskId, String input, String traceId) {
    String normalizedTaskId = taskId.length() >= 8 ? taskId : "task-" + UUID.randomUUID();
    String normalizedTrace = traceId.length() >= 8 ? traceId : UUID.randomUUID().toString();
    return Map.of(
        "contractVersion",
        "1.0",
        "workflowVersion",
        "three-fees-report-v1",
        "jobId",
        normalizedTaskId,
        "idempotencyKey",
        "idem-" + normalizedTaskId,
        "inputSha256",
        sha256(input),
        "traceId",
        normalizedTrace);
  }

  @SuppressWarnings("unchecked")
  private String extractJobId(Object requestBody) {
    var body = (Map<String, Object>) requestBody;
    return ((Map<String, String>) body.get("metadata")).get("jobId");
  }

  private String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  public record Fact(String fieldName, String value) {}

  public record AiImage(String fileName, String mediaType, byte[] bytes) {
    public AiImage {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }

  public record ReportSections(
      String title, String situation, String analysis, String rectification) {}

  public record AssistanceResult(String answer, ReportSections updatedSections) {}

  public record ReportImageAnalysisResult(
      String answer, String updatedContentHtml, String analysisText) {}
}
