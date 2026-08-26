package com.threefees.report.application;

import com.threefees.ai.application.AiServiceException;
import com.threefees.report.application.ReportDraftService.ImageAnalysisTaskPayload;
import com.threefees.task.application.TaskExecutionException;
import com.threefees.task.application.TaskProcessor;
import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class AiImageAnalysisTaskProcessor implements TaskProcessor {

  private final ObjectMapper objectMapper;
  private final ReportDraftService draftService;

  public AiImageAnalysisTaskProcessor(ObjectMapper objectMapper, ReportDraftService draftService) {
    this.objectMapper = objectMapper;
    this.draftService = draftService;
  }

  @Override
  public TaskType taskType() {
    return TaskType.AI_IMAGE_ANALYSIS;
  }

  @Override
  public String process(BusinessTask task) {
    ImageAnalysisTaskPayload payload = payload(task);
    try {
      draftService.completeImageAnalysisTask(
          payload.draftId(),
          payload.instruction(),
          payload.imageFileIds(),
          task.publicId(),
          task.createdBy());
      return result(payload.draftId());
    } catch (TaskExecutionException exception) {
      draftService.markImageAnalysisFailed(payload.draftId(), exception.code(), task.createdBy());
      throw exception;
    } catch (AiServiceException exception) {
      draftService.markImageAnalysisFailed(payload.draftId(), exception.code(), task.createdBy());
      throw new TaskExecutionException(exception.code(), exception.getMessage(), false);
    } catch (RuntimeException exception) {
      draftService.markImageAnalysisFailed(
          payload.draftId(), "AI_IMAGE_ANALYSIS_FAILED", task.createdBy());
      throw new TaskExecutionException(
          "AI_IMAGE_ANALYSIS_FAILED", "AI图片分析失败，请稍后重试", false);
    }
  }

  private ImageAnalysisTaskPayload payload(BusinessTask task) {
    try {
      return objectMapper.readValue(task.payloadJson(), ImageAnalysisTaskPayload.class);
    } catch (JacksonException exception) {
      throw new TaskExecutionException("TASK_PAYLOAD_INVALID", "AI图片分析任务载荷不正确", false);
    }
  }

  private String result(String draftId) {
    try {
      return objectMapper.writeValueAsString(java.util.Map.of("draftId", draftId));
    } catch (JacksonException exception) {
      throw new IllegalStateException("AI image analysis result could not be serialized", exception);
    }
  }
}
