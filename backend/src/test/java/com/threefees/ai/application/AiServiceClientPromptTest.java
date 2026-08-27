package com.threefees.ai.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiServiceClientPromptTest {

  @Test
  void imageAnalysisUsesShortRealAuditReportStyleGuide() {
    String guide = AiServiceClient.reportStyleGuide("IMAGE_ANALYSIS");

    assertThat(guide)
        .contains("你正在生成“电费稽核说明”工作稿，不是在聊天")
        .contains("真实电费稽核说明短格式")
        .contains("【固定结构】")
        .contains("【二、排查分析必须严格按这个顺序写】")
        .contains("【输出前自检，必须全部满足】")
        .contains("二、排查分析：先放图片说明及图片，最后放本期超标原因分析")
        .contains("真正的业务原因和关键核查结论用 strong")
        .contains("原因标签不要加粗，具体原因才加粗")
        .contains("纯数据对比不要加粗")
        .contains("本期日均用电量43.87度，同比正常上限42.05度，超标4.33%")
        .contains("一条原因一个 <p> 段落")
        .contains("不能用于图片说明、原因标签或纯数据对比")
        .contains("标题必须为“{报账点名称}电费稽核说明”")
        .contains("一、情况说明")
        .contains("二、排查分析")
        .contains("三、整改小结")
        .contains("不超过 80 字")
        .contains("不超过 120 字")
        .contains("图片说明只允许出现在“二、排查分析”章节内")
        .contains("“一、情况说明”和“三、整改小结”中的图片上方不要加说明文字")
        .contains("按用户粘贴图片的原始顺序保留全部图片，不得移动图片，不得遗漏图片")
        .contains("系统截图、缴费截图、标杆截图、位置点截图、表格截图、票据截图、附件截图，不写任何图片说明")
        .contains("禁止写“缴费信息界面图”“缴费标杆信息界面图”“位置点信息界面图”“系统界面图”“表格截图”“缴费截图”“标杆截图”等说明文字")
        .contains("全部图片结束后，才写本期超标原因分析")
        .contains("原因分析必须是“二、排查分析”的最后一段")
        .contains("必须位于全部图片之后、三、整改小结之前")
        .contains("格式必须是“说明文字：”然后紧跟图片")
        .contains("设备情况：")
        .contains("机房全景图：")
        .contains("本期电量同比超标原因")
        .contains("额定功率标杆")
        .contains("无历史报告或城市记忆")
        .contains("不得把“待核实”作为默认答案")
        .contains("同报账点历史案例中已有明确原因")
        .contains("资管系统未及时更新、额定功率台账未及时更新、现场设备功率未纳入系统")
        .contains("夏季5月-10月使用（主设备功率+空调总功率）*24小时*账期天数")
        .contains("非夏季1月-4月、11月-12月默认只使用主设备功率*24小时*账期天数")
        .contains("可能与空调运行有关，需人工确认")
        .contains("不得把铁塔空调总额定功率和空调总功率重复叠加")
        .contains("三费系统中对应额定功率标杆应为（2.549KW）*24小时*31天=1896.46度")
        .contains("不允许从图片或铭牌自行猜测功率")
        .contains("整改小结必须跟随本期原因变化")
        .contains("不需要写“已更新台账”“需更新台账”")
        .contains("关键核查结论可加粗")
        .contains("参考优先级固定")
        .contains("同报账点历史案例")
        .contains("本城市历史正式报告和用户纠正")
        .contains("江苏其他城市相似案例")
        .contains("历史图片说明写法")
        .contains("制式+厂家+BBU/RRU/AAU+数量")
        .contains("只有明确拍到通信主设备、BBU/RRU/AAU/天线/电源柜等设备细节的设备图，才写“设备情况：”清单")
        .contains("只识别出部分信息时写可确认部分")
        .contains("机房全景图、铁塔现场图、站点远景图只写“机房全景图：”“铁塔现场图：”等普通图前说明")
        .contains("只有设备图上方写设备清单")
        .contains("设备情况：现场可见通信主设备。")
        .contains("机房全景图、铁塔现场图、站点远景图不写设备清单")
        .contains("一旦已写设备清单式描述")
        .contains("不要再写“某某设备供电正常”“接线正常”等状态描述")
        .contains("已经写出设备清单式说明时，不要再写“供电正常”“接线正常”“现场正常”等状态结论")
        .contains("先仿照同点历史报告的写法组织正文")
        .contains("才按固定结构、图片说明规则、原因分析写法和整改小结写法兜底")
        .contains("模仿的是写法和表达，不是复制历史结论")
        .contains("可以直接导出 Word 的历史稽核报告正文")
        .contains("禁止写“同点历史”“本市经验”“同城市历史”“外市参考”“证据来源”等内部检索话术")
        .contains("禁止把“需复核更新台账”“需复核并更新额定功率台账”“现场设备台账与系统额定功率标杆存在偏差”作为默认结论")
        .contains("“额亏”必须改为“额定”")
        .contains("“阙值”必须改为“阈值”或当前口径“正常上限”")
        .contains("“集稽核”必须改为“稽核”")
        .contains("禁止用“系”代替“是”")
        .contains("不得输出“&#x20;”等 HTML 实体残留")
        .contains("不得保留异常多余空格、多余空白行")
        .contains("strong 是否只用于具体原因和关键核查结论")
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
        .contains("写作流程优先级：先仿写输入中的同报账点历史报告")
        .contains("才按通用稽核说明规则兜底")
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
