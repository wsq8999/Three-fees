package com.threefees.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiServiceClientPromptTest {

  @Test
  void imageAnalysisUsesShortRealAuditReportStyleGuide() {
    String guide = AiServiceClient.reportStyleGuide("IMAGE_ANALYSIS");

    assertThat(guide)
        .contains("真实电费稽核说明短格式")
        .contains("标题必须为“{报账点名称}电费稽核说明”")
        .contains("一、情况说明")
        .contains("二、排查分析")
        .contains("三、整改小结")
        .contains("不超过 80 字")
        .contains("不超过 120 字")
        .contains("图片说明只允许添加在“二、排查分析”章节内")
        .contains("“一、情况说明”和“三、整改小结”中的图片上方不要加说明文字")
        .contains("只给设备图、机房图添加图片说明")
        .contains("系统截图、缴费截图、表格截图、票据、附件截图等非设备/非机房图片上方不要加说明")
        .contains("格式为“说明文字：”然后紧跟图片")
        .contains("不得写在图片下方")
        .contains("设备情况：")
        .contains("机房全景图：")
        .contains("本期电量同比超标原因")
        .contains("额定功率标杆")
        .contains("无历史报告或城市记忆")
        .contains("参考优先级固定")
        .contains("同报账点历史案例")
        .contains("本城市历史正式报告和用户纠正")
        .contains("江苏其他城市相似案例")
        .contains("禁止出现“同点历史”“本市经验”“同城市历史”“外市参考”“证据来源”等内部检索话术")
        .contains("待核实")
        .contains("禁止虚构")
        .doesNotContain("每个 <figure data-file-id=\"...\"> 上方必须紧贴一行简短图片说明");
  }

  @Test
  void systemPromptKeepsExternalProvinceCasesSeparatedAsFallback() {
    assertThat(AiServiceClient.SYSTEM_PROMPT)
        .contains("当前城市经验优先服务当前城市")
        .contains("江苏其他城市相似案例")
        .contains("外市参考")
        .contains("禁止覆盖本市经验")
        .contains("内部检索来源只用于后台判断")
        .contains("禁止在报告正文中写“同点历史”“本市经验”“外市参考”“证据来源”等话术")
        .doesNotContain("禁止引用或推断其他城市的业务经验");
  }

  @Test
  void nonImageAnalysisDoesNotForceShortAuditReportStyle() {
    assertThat(AiServiceClient.reportStyleGuide("EDIT"))
        .contains("沿用当前报告结构")
        .doesNotContain("不超过 80 字");
  }
}
