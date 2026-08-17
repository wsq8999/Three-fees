package com.threefees.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.threefees.ai.application.AiServiceClient.AgentContext;
import com.threefees.ai.application.AiServiceClient.ReportSections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {"app.ai.enabled=true", "app.bootstrap.enabled=false"})
@EnabledIfEnvironmentVariable(named = "KIMI_SMOKE_TEST", matches = "true")
class AiServiceClientKimiSmokeTest {

  @Autowired private AiServiceClient client;

  @DynamicPropertySource
  static void kimiProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.ai.openai.api-key", () -> System.getenv("KIMI_API_KEY"));
    registry.add("spring.ai.openai.base-url", () -> "https://api.moonshot.cn/v1");
    registry.add("spring.ai.openai.chat.model", () -> "kimi-k3");
    registry.add("spring.ai.openai.chat.options.reasoning-effort", () -> "low");
    registry.add("spring.ai.openai.chat.options.max-completion-tokens", () -> 8192);
    registry.add("spring.ai.openai.chat.options.timeout", () -> "10m");
    registry.add("app.ai.model", () -> "kimi-k3");
  }

  @Test
  void answersSimpleQuestionThroughSpringAi() {
    var response =
        client.assist(
            "smoke-test",
            "ASK",
            "1加1等于几？",
            new ReportSections("测试", "测试", "测试", "测试"),
            List.of(),
            List.of(),
            AgentContext.empty(),
            "smoke-test");

    assertThat(response.answer()).isNotBlank();
    assertThat(response.updatedSections()).isNull();
  }

  @Test
  void rewritesReportThroughSpringAi() {
    var response =
        client.assist(
            "smoke-edit",
            "EDIT",
            "请根据现有内容重写完整报告，证据不足时明确待核实。",
            new ReportSections("测试报告", "系统判定超标。", "当前证据不足。", "补充现场材料。"),
            List.of(),
            List.of(),
            AgentContext.empty(),
            "smoke-edit");

    assertThat(response.answer()).isNotBlank();
    assertThat(response.updatedSections()).isNotNull();
    assertThat(response.updatedSections().analysis()).isNotBlank();
  }
}
