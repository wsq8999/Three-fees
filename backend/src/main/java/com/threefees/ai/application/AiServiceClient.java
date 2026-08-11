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

  public AssistanceResult assist(
      String taskId,
      String intent,
      String instruction,
      ReportSections sections,
      List<Fact> facts,
      List<AiImage> images,
      String traceId) {
    Map<String, Object> request =
        Map.of(
            "metadata", metadata(taskId, instruction, traceId),
            "intent", intent,
            "instruction", instruction,
            "currentSections", sections,
            "facts", facts,
            "images",
                images.stream()
                    .map(
                        image ->
                            Map.of(
                                "fileName", image.fileName(),
                                "mediaType", image.mediaType(),
                                "base64Data", Base64.getEncoder().encodeToString(image.bytes())))
                    .toList(),
            "allowedEvidenceIds", List.of());
    JsonNode response = post("internal/v1/report-assistances", request, traceId);
    JsonNode updated = response.get("updatedSections");
    ReportSections updatedSections =
        updated == null || updated.isNull()
            ? null
            : objectMapper.convertValue(updated, ReportSections.class);
    return new AssistanceResult(response.path("answer").asText(), updatedSections);
  }

  public ReportSections compose(
      String taskId, List<Fact> facts, boolean overLimit, String reason, String traceId) {
    Map<String, Object> request =
        Map.of(
            "metadata", metadata(taskId, reason, traceId),
            "facts", facts,
            "judgment",
                Map.of(
                    "overLimit", overLimit,
                    "reasonSummary", reason,
                    "citedEvidenceIds", List.of()),
            "allowedEvidenceIds", List.of());
    JsonNode response = post("internal/v1/report-compositions", request, traceId);
    return objectMapper.convertValue(response.path("sections"), ReportSections.class);
  }

  private JsonNode post(String path, Object requestBody, String traceId) {
    if (token == null || token.length() < 16) {
      throw new AiServiceException("AI_SERVICE_NOT_CONFIGURED", "AI_SERVICE_TOKEN 未配置或长度不足", false);
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
        throw new AiServiceException("AI_RATE_LIMITED", "AI 服务限流", true);
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new AiServiceException(
            "AI_SERVICE_ERROR",
            "AI 服务调用失败，HTTP " + response.statusCode(),
            response.statusCode() >= 500);
      }
      JsonNode json = objectMapper.readTree(response.body());
      if (!json.path("metadata").path("jobId").asText().equals(extractJobId(requestBody))) {
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
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
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
}
