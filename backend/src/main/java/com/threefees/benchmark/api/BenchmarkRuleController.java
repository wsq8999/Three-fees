package com.threefees.benchmark.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/benchmark-rules")
public class BenchmarkRuleController {

  @GetMapping
  public List<BenchmarkRuleResponse> list() {
    return List.of(
        new BenchmarkRuleResponse(
            "YEAR_ON_YEAR",
            "历史日均电量同比标杆",
            "audit-rule-v2026.1",
            "以上一年度同月审核通过缴费明细的日均耗电量字段为参考，并按标杆变化系数上浮形成正常上限。",
            "C=上年同月缴费明细日均耗电量；A=本月额定功率标杆月总值÷本月缴费天数；B=上年同月额定功率标杆月总值÷参考月缴费天数；K=max(1,A/B)；正常上限=C×K×1.20。",
            List.of("筛选上年同月审核通过缴费单", "读取参考月日均耗电量 C", "计算标杆变化系数 K", "计算正常上限 C×K×1.20", "本期日均耗电量大于正常上限则超标"),
            List.of(
                new RuleExample("参考月日均 C", "260.00 kWh/天"),
                new RuleExample("调整系数 K", "1.1500"),
                new RuleExample("正常上限", "358.80 kWh/天")),
            List.of("缺少 A/B/C 任一数据时不适用", "缴费天数取缴费明细字段", "参考月缴费单必须审核通过", "低于或等于正常上限不判异常"),
            "正式报告保存计算输入、输出与规则版本快照。"),
        new BenchmarkRuleResponse(
            "MONTH_ON_MONTH",
            "历史日均电量环比标杆",
            "audit-rule-v2026.1",
            "向前寻找最近一笔审核通过缴费明细作为参考，并按标杆变化系数上浮形成正常上限。",
            "C=上一笔审核通过缴费明细日均耗电量；A=本月额定功率标杆月总值÷本月缴费天数；B=参考账期额定功率标杆月总值÷参考账期缴费天数；K=max(1,A/B)；正常上限=C×K×1.20。",
            List.of("向前查找最近一笔审核通过缴费单", "读取参考账期日均耗电量 C", "计算标杆变化系数 K", "计算正常上限 C×K×1.20", "本期日均耗电量大于正常上限则超标"),
            List.of(
                new RuleExample("参考月日均 C", "354.84 kWh/天"),
                new RuleExample("调整系数 K", "1.0000"),
                new RuleExample("正常上限", "425.81 kWh/天")),
            List.of("缺少 A/B/C 任一数据时不适用", "跳过缴费单未审核通过的账期", "低于或等于正常上限不判异常", "小数使用十进制定点精度"),
            "重新导入激活批次后重新查找上一有效月并生成新审计快照。"),
        new BenchmarkRuleResponse(
            "RATED_BENCHMARK",
            "额定标杆",
            "benchmark-v2026.1",
            "比较本期缴费明细实际总电量与当月额定功率标杆月总值形成的正常上限。",
            "正常上限=当月额定功率标杆月总值；实际总耗电量>正常上限则超标。",
            List.of("汇总本期缴费明细实际总电量", "读取当月额定功率标杆月总值", "比较实际总量与正常上限", "形成最终超标类型"),
            List.of(
                new RuleExample("实际总电量 A", "12,850.36 kWh"),
                new RuleExample("正常上限 D", "11,500.00 kWh"),
                new RuleExample("偏差率", "11.74%")),
            List.of("当月额定功率标杆月总值缺失时不适用", "低于或等于正常上限不判异常", "正常上限为0且实际为0时正常", "正常上限为0且实际大于0时超标且不显示无穷比例"),
            "报告始终展示生成时使用的规则版本，不随后续规则说明变化而回写。"));
  }

  public record BenchmarkRuleResponse(
      String key,
      String name,
      String version,
      String description,
      String formula,
      List<String> chain,
      List<RuleExample> example,
      List<String> boundaries,
      String snapshotNote) {}

  public record RuleExample(String label, String value) {}
}
