package com.threefees.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.threefees.ai.application.AiServiceException;
import com.threefees.ai.application.AiServiceClient.ReportSections;
import com.threefees.report.application.ReportDraftService.ImageAnalysisTaskPayload;
import com.threefees.task.application.TaskExecutionException;
import com.threefees.task.domain.BusinessTask;
import com.threefees.task.domain.TaskStatus;
import com.threefees.task.domain.TaskType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AiImageAnalysisTaskProcessorTest {

  @Test
  void imageAnalysisFailureDoesNotAutoRetryToAvoidExtraKimiCalls() throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    ReportDraftService draftService = mock(ReportDraftService.class);
    AiImageAnalysisTaskProcessor processor =
        new AiImageAnalysisTaskProcessor(objectMapper, draftService);
    String payload =
        objectMapper.writeValueAsString(
            new ImageAnalysisTaskPayload(
                "draft-1",
                1,
                "分析图片",
                List.of("file-1"),
                new ReportSections("标题", "<p>情况</p>", "<p>分析</p>", "<p>整改</p>")));
    BusinessTask task =
        new BusinessTask(
            1L,
            "task-1",
            TaskType.AI_IMAGE_ANALYSIS,
            "AI_IMAGE_ANALYSIS:DRAFT:draft-1:CONTENT_VERSION:1",
            TaskStatus.RUNNING,
            1,
            3,
            null,
            null,
            null,
            payload,
            null,
            null,
            LocalDateTime.now(),
            "tester",
            LocalDateTime.now(),
            0L);
    doThrow(new AiServiceException("KIMI_TIMEOUT", "Kimi 调用超时", true))
        .when(draftService)
        .completeImageAnalysisTask("draft-1", "分析图片", List.of("file-1"), "task-1", "tester");

    assertThatThrownBy(() -> processor.process(task))
        .isInstanceOf(TaskExecutionException.class)
        .satisfies(
            exception -> {
              TaskExecutionException taskException = (TaskExecutionException) exception;
              assertThat(taskException.code()).isEqualTo("KIMI_TIMEOUT");
              assertThat(taskException.retryable()).isFalse();
            });

    verify(draftService).markImageAnalysisFailed("draft-1", "KIMI_TIMEOUT", "tester");
  }
}
