package com.threefees.benchmark.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BenchmarkRuleControllerTest {

  @Test
  void benchmarkRulesDescribeNormalUpperLimitAndDailyBenchmarkTotals() {
    var rules = new BenchmarkRuleController().list();

    assertThat(rules)
        .allSatisfy(
            rule -> {
              assertThat(rule.formula()).contains("正常上限");
              assertThat(rule.formula()).doesNotContain("阈值");
              assertThat(String.join("，", rule.boundaries())).contains("低于或等于正常上限不判异常");
            });

    var yoy = rules.stream().filter(rule -> rule.key().equals("YEAR_ON_YEAR")).findFirst().orElseThrow();
    assertThat(yoy.formula()).contains("标杆日列合计", "C×K×1.20");
    assertThat(yoy.chain()).contains("当前日均大于正常上限则超标");
  }
}
