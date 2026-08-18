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
            "以上一年度同自然月的合格日均电量为参考，并按标杆变化系数上浮。",
            "A=本月标杆总量÷本月自然日；B=上年同月标杆总量÷参考月自然日；K=max(1,A/B)；同比阈值=C×K×1.20。",
            List.of("筛选上年同月合格参考月", "计算标杆变化系数", "计算同比阈值", "当前日均大于阈值则超标"),
            List.of(
                new RuleExample("参考月日均 C", "260.00 kWh/天"),
                new RuleExample("调整系数 K", "1.1500"),
                new RuleExample("阈值", "358.80 kWh/天")),
            List.of("缺少 A/B/C 任一数据时不适用", "闰年按各自自然月天数计算", "参考月缴费单必须审核通过"),
            "正式报告保存计算输入、输出与规则版本快照。"),
        new BenchmarkRuleResponse(
            "MONTH_ON_MONTH",
            "历史日均电量环比标杆",
            "audit-rule-v2026.1",
            "向前寻找最近的合格自然月作为参考月，不强制固定上一个月。",
            "A=本月标杆月平均；B=参考月标杆月平均；K=max(1,A/B)；环比阈值=C×K×1.20。",
            List.of("向前查找最近合格自然月", "计算标杆变化系数", "计算环比阈值", "当前日均大于阈值则超标"),
            List.of(
                new RuleExample("参考月日均 C", "354.84 kWh/天"),
                new RuleExample("调整系数 K", "1.0000"),
                new RuleExample("阈值", "425.81 kWh/天")),
            List.of("缺少 A/B/C 任一数据时不适用", "跳过不完整或缴费单未审核通过的月份", "小数使用十进制定点精度"),
            "重新导入激活批次后重新查找上一有效月并生成新审计快照。"),
        new BenchmarkRuleResponse(
            "RATED_BENCHMARK",
            "额定标杆",
            "benchmark-v2026.1",
            "比较有效电表行分摊后度数合计与适用的额定标杆。",
            "额定标杆总量=当月1日至月末有效日标杆值之和；实际总耗电量>额定标杆总量则超标。",
            List.of("汇总有效分摊后度数", "求和当月有效日标杆值", "比较实际总量与额定标杆总量", "形成最终超标类型"),
            List.of(
                new RuleExample("实际总电量 A", "12,850.36 kWh"),
                new RuleExample("额定标杆 D", "11,500.00 kWh"),
                new RuleExample("偏差率", "11.74%")
            ),
            List.of("日标杆不完整时不适用", "阈值为0且实际为0时正常", "阈值为0且实际大于0时超标且不显示无穷比例"),
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
