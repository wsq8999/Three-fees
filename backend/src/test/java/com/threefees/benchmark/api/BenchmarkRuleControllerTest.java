package com.threefees.benchmark.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BenchmarkRuleControllerTest {

  @Test
  void benchmarkRulesDescribeNormalUpperLimitAndPaymentDayBenchmarkAverages() {
    var rules = new BenchmarkRuleController().list();

    assertThat(rules)
        .allSatisfy(
            rule -> {
              assertThat(rule.formula()).contains("正常上限");
              assertThat(rule.formula()).doesNotContain("阈值");
              assertThat(String.join("，", rule.boundaries())).contains("低于或等于正常上限不判异常");
            });

    var yoy = rules.stream().filter(rule -> rule.key().equals("YEAR_ON_YEAR")).findFirst().orElseThrow();
    assertThat(yoy.formula()).contains("额定功率标杆月总值", "缴费天数", "C×K×1.20");
    assertThat(yoy.chain()).contains("本期日均耗电量大于正常上限则超标");
  }
}
