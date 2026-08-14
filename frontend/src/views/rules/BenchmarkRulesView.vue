<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ArrowRight } from "@element-plus/icons-vue";

import { businessApi } from "@/api/business-api";
import PageState from "@/components/PageState.vue";
import type { BenchmarkRule } from "@/types/business";

const rules = ref<BenchmarkRule[]>([]);
const selectedKey = ref<BenchmarkRule["key"]>("RATED_BENCHMARK");
const loading = ref(true);
const errorMessage = ref("");

const ruleHints: Record<BenchmarkRule["key"], string> = {
  YEAR_ON_YEAR: "固定对比去年同月",
  MONTH_ON_MONTH: "固定对比上一个自然月",
  RATED_BENCHMARK: "对比当月日标杆合计",
};

const selected = computed(
  () => rules.value.find((rule) => rule.key === selectedKey.value) ?? rules.value[0],
);

const selectedIndex = computed(() =>
  Math.max(0, rules.value.findIndex((rule) => rule.key === selected.value?.key)),
);

async function load(): Promise<void> {
  loading.value = true;
  errorMessage.value = "";
  try {
    rules.value = await businessApi.rules.list();
    if (rules.value.some((item) => item.key === "RATED_BENCHMARK")) {
      selectedKey.value = "RATED_BENCHMARK";
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "标杆规则加载失败";
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <PageState v-if="loading" kind="loading" />
  <PageState
    v-else-if="errorMessage"
    kind="error"
    title="标杆规则加载失败"
    :description="errorMessage"
    @retry="load"
  />
  <template v-else-if="selected">
    <section class="rules-header">
      <div>
        <h1>标杆规则管理</h1>
        <p>统一查看同比、环比、额定标杆三类稽核规则，规则只读，计算以系统后台为准。</p>
      </div>
      <dl>
        <div>
          <dt>规则数量</dt>
          <dd>{{ rules.length }}条</dd>
        </div>
        <div>
          <dt>同比/环比容忍</dt>
          <dd>20%</dd>
        </div>
        <div>
          <dt>当前版本</dt>
          <dd>V2.0</dd>
        </div>
        <div>
          <dt>运行状态</dt>
          <dd><ElTag type="success" effect="light">使用中</ElTag></dd>
        </div>
      </dl>
    </section>

    <div class="rules-layout">
      <aside class="rule-list">
        <div class="section-title">
          <h2>规则目录</h2>
          <span>只读</span>
        </div>
        <button
          v-for="(rule, index) in rules"
          :key="rule.key"
          type="button"
          :class="{ selected: selectedKey === rule.key }"
          @click="selectedKey = rule.key"
        >
          <b>{{ String(index + 1).padStart(2, "0") }}</b>
          <span>
            <strong>{{ rule.name }}</strong>
            <small>{{ ruleHints[rule.key] }}</small>
          </span>
        </button>

        <section class="decision-note">
          <strong>最终判定</strong>
          <p>任一适用规则超标，则本期稽核结果为超标；最大超标比例取三类有效结果中的最大值。</p>
        </section>
      </aside>

      <main class="rule-panel">
        <header>
          <div>
            <small>规则 {{ String(selectedIndex + 1).padStart(2, "0") }}</small>
            <h2>{{ selected.name }}</h2>
            <p>{{ selected.description }}</p>
          </div>
          <ElTag effect="plain">固定规则 · 只读</ElTag>
        </header>

        <section class="rule-section">
          <div class="section-title">
            <h3>计算步骤</h3>
          </div>
          <div class="calculation-chain">
            <template v-for="(step, index) in selected.chain" :key="step">
              <div class="step-card">
                <small>{{ String(index + 1).padStart(2, "0") }}</small>
                <strong>{{ step }}</strong>
              </div>
              <ElIcon v-if="index < selected.chain.length - 1"><ArrowRight /></ElIcon>
            </template>
          </div>
        </section>

        <div class="rule-grid">
          <section class="rule-section">
            <div class="section-title">
              <h3>计算公式</h3>
            </div>
            <p>{{ selected.formula }}</p>
          </section>
          <section class="rule-section">
            <div class="section-title">
              <h3>边界与例外</h3>
            </div>
            <p>{{ selected.boundaries.join("；") }}</p>
          </section>
        </div>

        <section class="rule-section">
          <div class="section-title">
            <h3>示例演算</h3>
          </div>
          <div class="example-grid">
            <span v-for="item in selected.example" :key="item.label">
              <small>{{ item.label }}</small>
              <strong>{{ item.value }}</strong>
            </span>
          </div>
        </section>

        <div class="rule-grid">
          <section class="rule-section">
            <div class="section-title">
              <h3>标杆值 Excel 校验</h3>
            </div>
            <div class="validation-list">
              <span><b>自然月完整</b><small>1日至月末均有有效日值</small></span>
              <span><b>越界日期为空</b><small>如2月31日必须为空</small></span>
              <span><b>月平均一致</b><small>允许导入误差小于0.01</small></span>
            </div>
          </section>
          <section class="rule-section">
            <div class="section-title">
              <h3>版本与快照</h3>
            </div>
            <p>{{ selected.snapshotNote }}</p>
          </section>
        </div>
      </main>
    </div>
  </template>
</template>

<style scoped>
.rules-header,
.rule-list,
.rule-panel,
.rule-section {
  background: #fff;
  border: 1px solid #e3e8f0;
  border-radius: 8px;
}

.rules-header {
  display: flex;
  gap: 20px;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px;
  margin-bottom: 14px;
}

.rules-header h1,
.rules-header p,
.rules-header dl,
.rules-header dd,
.section-title h2,
.section-title h3 {
  margin: 0;
}

.rules-header h1 {
  color: #10203a;
  font-size: 20px;
  font-weight: 800;
}

.rules-header p {
  margin-top: 6px;
  color: #66758a;
  font-size: 13px;
}

.rules-header dl {
  display: grid;
  min-width: min(100%, 520px);
  grid-template-columns: repeat(4, minmax(96px, 1fr));
}

.rules-header dl > div {
  padding-left: 16px;
  border-left: 1px solid #e7edf5;
}

.rules-header dt {
  color: #7b8798;
  font-size: 12px;
}

.rules-header dd {
  margin-top: 5px;
  color: #10203a;
  font-weight: 800;
}

.rules-layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 14px;
}

.rule-list,
.rule-panel {
  min-width: 0;
  padding: 16px;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.section-title h2,
.section-title h3 {
  color: #10203a;
  font-size: 16px;
  font-weight: 800;
}

.section-title span {
  color: #8a96a8;
  font-size: 12px;
}

.rule-list button {
  display: flex;
  width: 100%;
  gap: 12px;
  align-items: center;
  padding: 13px 12px;
  margin-top: 10px;
  color: #26364f;
  text-align: left;
  background: #fff;
  border: 1px solid #e5ebf3;
  border-radius: 8px;
  cursor: pointer;
}

.rule-list button.selected {
  color: #ef233c;
  background: #fff4f5;
  border-color: #ffc6cf;
}

.rule-list button b {
  color: inherit;
  font-size: 16px;
}

.rule-list button span {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.rule-list button small {
  color: #7b8798;
}

.decision-note {
  padding: 14px;
  margin-top: 14px;
  background: #fff8e8;
  border: 1px solid #f7dfac;
  border-radius: 8px;
}

.decision-note p {
  margin: 6px 0 0;
  color: #76531a;
  font-size: 13px;
  line-height: 1.7;
}

.rule-panel > header {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  justify-content: space-between;
  padding-bottom: 14px;
  border-bottom: 1px solid #e5ebf3;
}

.rule-panel > header small {
  color: #7b8798;
}

.rule-panel > header h2 {
  margin: 4px 0;
  color: #10203a;
  font-size: 20px;
  font-weight: 800;
}

.rule-panel > header p,
.rule-section p {
  margin: 0;
  color: #4e5f78;
  line-height: 1.75;
}

.rule-panel > header p {
  max-width: 760px;
}

.rule-section {
  padding: 14px;
  margin-top: 14px;
}

.rule-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 14px;
}

.calculation-chain {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: stretch;
  margin-top: 12px;
}

.calculation-chain .el-icon {
  align-self: center;
  color: #a5afbf;
}

.step-card {
  display: flex;
  min-width: min(100%, 180px);
  flex: 1;
  flex-direction: column;
  gap: 6px;
  justify-content: center;
  padding: 12px;
  background: #f8fafc;
  border: 1px solid #e7edf5;
  border-radius: 8px;
}

.step-card small,
.example-grid small,
.validation-list small {
  color: #7b8798;
}

.step-card strong {
  color: #10203a;
}

.example-grid,
.validation-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 150px), 1fr));
  gap: 10px;
  margin-top: 12px;
}

.example-grid span,
.validation-list span {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 5px;
  padding: 12px;
  background: #f8fafc;
  border: 1px solid #edf1f6;
  border-radius: 8px;
}

.example-grid strong {
  color: #ef233c;
}

@media (width <= 1100px) {
  .rules-header {
    align-items: stretch;
    flex-direction: column;
  }

  .rules-header dl,
  .rules-layout,
  .rule-grid {
    grid-template-columns: 1fr;
  }
}

@media (width <= 640px) {
  .rules-header dl {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    row-gap: 12px;
  }

  .rules-header dl > div {
    padding-left: 10px;
  }
}
</style>
