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

const selected = computed(
  () =>
    rules.value.find((rule) => rule.key === selectedKey.value) ??
    rules.value[0],
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
    errorMessage.value =
      error instanceof Error ? error.message : "标杆规则加载失败";
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
    <section class="rules-summary">
      <div>
        <h1>江苏省电费稽核规则</h1>
        <p>统一维护三类只读标杆，额定标杆取自每月导入数据</p>
      </div>
      <dl>
        <div>
          <dt>规则数量</dt>
          <dd>{{ rules.length }}条</dd>
        </div>
        <div>
          <dt>同比/环比上浮</dt>
          <dd>20%</dd>
        </div>
        <div>
          <dt>当前版本</dt>
          <dd>V2.0</dd>
        </div>
        <div>
          <dt>运行状态</dt>
          <dd><ElTag type="success">使用中</ElTag></dd>
        </div>
      </dl>
    </section>

    <div class="rules-workspace">
      <aside class="rule-directory">
        <h2>规则目录</h2>
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
            <small>{{
              rule.key === "YEAR_ON_YEAR"
                ? "去年相同自然月"
                : rule.key === "MONTH_ON_MONTH"
                  ? "最近一笔审核通过记录"
                  : "按月汇总每日标杆值"
            }}</small>
          </span>
        </button>

        <section class="final-decision">
          <strong>最终判定</strong>
          <p>任一适用标杆超标，最终结果为超标；最大超标比例取有效结果最大值。</p>
        </section>
      </aside>

      <main class="rule-detail">
        <header>
          <div>
            <small>规则 {{ String(selectedIndex + 1).padStart(2, "0") }}</small>
            <h2>{{ selected.name }}</h2>
            <p>{{ selected.description }}</p>
          </div>
          <ElTag type="info">固定规则 · 只读</ElTag>
        </header>

        <div class="calculation-chain">
          <template v-for="(step, index) in selected.chain" :key="step">
            <section>
              <small>{{ String(index + 1).padStart(2, "0") }} 计算步骤</small>
              <strong>{{ step }}</strong>
            </section>
            <ElIcon v-if="index < selected.chain.length - 1"><ArrowRight /></ElIcon>
          </template>
        </div>

        <div class="info-grid">
          <section class="blue-note">
            <h3>计算补充</h3>
            <p>{{ selected.formula }}</p>
          </section>
          <section>
            <h3>边界与例外</h3>
            <p>{{ selected.boundaries.join("；") }}</p>
          </section>
        </div>

        <section class="example-card">
          <h3>示例演算</h3>
          <div>
            <span v-for="item in selected.example" :key="item.label">
              <small>{{ item.label }}</small>
              <strong>{{ item.value }}</strong>
            </span>
          </div>
        </section>

        <div class="bottom-grid">
          <section>
            <h3>标杆值Excel校验规则</h3>
            <div class="validation-items">
              <span><b>自然月完整</b><small>1日至月末均有值</small></span>
              <span><b>越界日为空</b><small>如6月31日必须为空</small></span>
              <span><b>月平均一致</b><small>允许导入差≤0.01</small></span>
            </div>
          </section>
          <section>
            <h3>版本与快照</h3>
            <p>{{ selected.snapshotNote }}</p>
          </section>
        </div>
      </main>
    </div>
  </template>
</template>

<style scoped>
.rules-summary,
.rule-directory,
.rule-detail {
  background: #fff;
  border: 1px solid #dde5ef;
  border-radius: 12px;
  box-shadow: 0 6px 18px rgb(25 42 70 / 6%);
}

.rules-summary {
  display: flex;
  gap: 24px;
  align-items: center;
  justify-content: space-between;
  padding: 18px 22px;
  margin-bottom: 14px;
}

.rules-summary h1,
.rules-summary p,
.rules-summary dl,
.rules-summary dd {
  margin: 0;
}

.rules-summary h1 {
  color: #152137;
  font-size: 22px;
  font-weight: 800;
}

.rules-summary p {
  margin-top: 4px;
  color: #64748a;
  font-size: 13px;
}

.rules-summary dl {
  display: grid;
  grid-template-columns: repeat(4, auto);
}

.rules-summary dl > div {
  min-width: 110px;
  padding: 0 16px;
  border-left: 1px solid #e1e7ef;
}

.rules-summary dt {
  color: #64748a;
  font-size: 12px;
}

.rules-summary dd {
  margin-top: 4px;
  color: #14213a;
  font-weight: 800;
}

.rules-workspace {
  display: grid;
  min-height: calc(100vh - 168px);
  grid-template-columns: 290px minmax(0, 1fr);
  gap: 14px;
}

.rule-directory,
.rule-detail {
  padding: 20px;
}

.rule-directory h2,
.rule-detail h2,
.rule-detail h3 {
  margin: 0;
}

.rule-directory button {
  display: flex;
  width: 100%;
  min-height: 86px;
  gap: 16px;
  align-items: center;
  padding: 16px;
  margin-top: 14px;
  color: #23324a;
  text-align: left;
  background: #fff;
  border: 1px solid transparent;
  border-radius: 10px;
  cursor: pointer;
}

.rule-directory button.selected {
  background: #fff4f5;
  border-color: #f03349;
}

.rule-directory button b {
  color: #f03349;
  font-size: 18px;
}

.rule-directory button span {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.rule-directory button small {
  color: #66758a;
}

.final-decision {
  padding: 16px;
  margin-top: 18px;
  background: #fff7e6;
  border-radius: 10px;
}

.final-decision p {
  margin: 6px 0 0;
  color: #7d5b16;
  font-size: 13px;
}

.rule-detail > header {
  display: flex;
  justify-content: space-between;
  padding-bottom: 14px;
  border-bottom: 1px solid #e1e7ef;
}

.rule-detail > header small {
  color: #66758a;
}

.rule-detail > header h2 {
  margin-top: 4px;
  color: #14213a;
  font-size: 22px;
}

.rule-detail > header p {
  margin: 4px 0 0;
  color: #64748a;
}

.calculation-chain {
  display: flex;
  gap: 10px;
  align-items: center;
  margin: 16px 0;
}

.calculation-chain section {
  display: flex;
  min-height: 90px;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  justify-content: center;
  padding: 14px;
  border: 1px solid #dfe6ef;
  border-radius: 10px;
}

.calculation-chain small {
  color: #66758a;
}

.info-grid,
.bottom-grid {
  display: grid;
  grid-template-columns: 1.3fr 1fr;
  gap: 12px;
  margin-bottom: 12px;
}

.info-grid section,
.bottom-grid section,
.example-card {
  padding: 14px;
  border: 1px solid #dfe6ef;
  border-radius: 10px;
}

.blue-note {
  background: #eef5ff;
  border-color: #d7e7ff !important;
}

.info-grid p,
.bottom-grid p {
  margin: 8px 0 0;
  color: #40506a;
}

.example-card {
  margin-bottom: 12px;
}

.example-card > div {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-top: 12px;
}

.example-card span,
.validation-items span {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 12px;
  background: #f8fafc;
  border-radius: 8px;
}

.example-card small,
.validation-items small {
  color: #66758a;
}

.example-card strong {
  color: #f03349;
}

.validation-items {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin-top: 12px;
}

@media (width <= 1100px) {
  .rules-summary,
  .rules-workspace {
    grid-template-columns: 1fr;
  }

  .rules-summary {
    align-items: flex-start;
    flex-direction: column;
  }

  .rules-summary dl,
  .info-grid,
  .bottom-grid,
  .example-card > div {
    grid-template-columns: 1fr;
  }
}
</style>
