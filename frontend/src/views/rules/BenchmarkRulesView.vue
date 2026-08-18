<script setup lang="ts">
import { computed, onMounted, ref } from "vue";

import { businessApi } from "@/api/business-api";
import PageState from "@/components/PageState.vue";
import type { BenchmarkRule } from "@/types/business";

const rules = ref<BenchmarkRule[]>([]);
const selectedKey = ref<BenchmarkRule["key"]>("RATED_BENCHMARK");

const loading = ref(true);
const errorMessage = ref("");

const selected = computed(() => {
  return (
    rules.value.find((item) => item.key === selectedKey.value) ??
    rules.value[0]
  );
});

/**
 * 顶部规则切换名称
 */
function getTabName(key: BenchmarkRule["key"]): string {
  switch (key) {
    case "YEAR_ON_YEAR":
      return "同比标杆";

    case "MONTH_ON_MONTH":
      return "环比标杆";

    case "RATED_BENCHMARK":
      return "额定标杆";

    default:
      return "标杆";
  }
}

/**
 * 三类标杆简要用途说明
 */
function getRuleHint(key: BenchmarkRule["key"]): string {
  switch (key) {
    case "YEAR_ON_YEAR":
      return "用于与去年同月历史用电情况进行比较";

    case "MONTH_ON_MONTH":
      return "用于与上一个自然月历史用电情况进行比较";

    case "RATED_BENCHMARK":
      return "用于与当前账期适用的额定标杆值进行比较";

    default:
      return "";
  }
}

/**
 * 加载标杆规则
 */
async function load(): Promise<void> {
  loading.value = true;
  errorMessage.value = "";

  try {
    rules.value = await businessApi.rules.list();

    if (
      rules.value.some(
        (item) => item.key === "RATED_BENCHMARK"
      )
    ) {
      selectedKey.value = "RATED_BENCHMARK";
    } else if (rules.value.length > 0) {
      selectedKey.value = rules.value[0].key;
    }
  } catch (error) {
    errorMessage.value =
      error instanceof Error
        ? error.message
        : "标杆规则加载失败";
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <!-- 加载中 -->
  <PageState
    v-if="loading"
    kind="loading"
  />

  <!-- 加载失败 -->
  <PageState
    v-else-if="errorMessage"
    kind="error"
    title="标杆规则加载失败"
    :description="errorMessage"
    @retry="load"
  />

  <!-- 正常内容 -->
  <template v-else-if="selected">
    <!-- 标杆切换 -->
    <section
      class="rule-switch-card business-card"
      aria-label="标杆切换"
    >
      <div class="rule-switch-list">
        <button
          v-for="rule in rules"
          :key="rule.key"
          type="button"
          class="rule-switch-item"
          :class="{
            active: selectedKey === rule.key,
          }"
          @click="selectedKey = rule.key"
        >
          {{ getTabName(rule.key) }}
        </button>
      </div>
    </section>

    <!-- 标杆详情 -->
    <section class="rule-detail-card business-card">
      <!-- 标题 -->
      <header class="rule-detail-header">
        <div class="rule-title-area">
          <h2>
            {{ selected.name }}
          </h2>

          <p class="rule-description">
            {{ selected.description }}
          </p>

          <p class="rule-hint">
            {{ getRuleHint(selected.key) }}
          </p>
        </div>
      </header>

      <!-- 计算规则 -->
      <section class="detail-section">
        <h3 class="section-heading">
          计算规则
        </h3>

        <div class="info-list">
          <div class="info-row">
            <div class="info-label">
              计算公式
            </div>

            <div class="info-value formula-value">
              {{ selected.formula }}
            </div>
          </div>

          <div
            v-if="selected.boundaries?.length"
            class="info-row"
          >
            <div class="info-label">
              边界与例外
            </div>

            <div class="info-value">
              <ul class="plain-list">
                <li
                  v-for="item in selected.boundaries"
                  :key="item"
                >
                  {{ item }}
                </li>
              </ul>
            </div>
          </div>
        </div>
      </section>

      <!-- 计算步骤 -->
      <section
        v-if="selected.chain?.length"
        class="detail-section"
      >
        <h3 class="section-heading">
          计算步骤
        </h3>

        <div class="step-list">
          <template
            v-for="(step, index) in selected.chain"
            :key="`${selected.key}-${index}`"
          >
            <div class="step-item">
              <span class="step-number">
                {{ index + 1 }}
              </span>

              <span class="step-text">
                {{ step }}
              </span>
            </div>

            <span
              v-if="
                index <
                selected.chain.length - 1
              "
              class="step-divider"
            >
              →
            </span>
          </template>
        </div>
      </section>

      <!-- 示例演算 -->
      <section
        v-if="selected.example?.length"
        class="detail-section"
      >
        <h3 class="section-heading">
          示例演算
        </h3>

        <div class="example-table">
          <ElTable
            :data="[selected]"
            border
            style="width: 100%"
          >
            <ElTableColumn
              v-for="item in selected.example"
              :key="item.label"
              :label="item.label"
              min-width="130"
              align="center"
            >
              <template #default>
                {{ item.value }}
              </template>
            </ElTableColumn>
          </ElTable>
        </div>
      </section>

      <!-- 额定标杆数据校验 -->
      <section
        v-if="
          selected.key === 'RATED_BENCHMARK'
        "
        class="detail-section"
      >
        <h3 class="section-heading">
          数据校验要求
        </h3>

        <div class="info-list">
          <div class="info-row">
            <div class="info-label">
              自然月完整
            </div>

            <div class="info-value">
              1日至月末均应存在有效日标杆值。
            </div>
          </div>

          <div class="info-row">
            <div class="info-label">
              越界日期为空
            </div>

            <div class="info-value">
              当月不存在的日期应保持为空，
              例如2月份不存在的日期不得填写数据。
            </div>
          </div>

          <div class="info-row">
            <div class="info-label">
              数据一致
            </div>

            <div class="info-value">
              导入数据应符合系统规定的字段格式及数值校验要求。
            </div>
          </div>
        </div>
      </section>

      <!-- 最终判定 -->
      <section class="final-section">
        <div class="final-title">
          最终判定
        </div>

        <div class="final-content">
          任一适用标杆超标，则当前报账点当前账期的稽核结果判定为超标；
          最大超标比例取同比、环比、额定标杆三类有效计算结果中的最大值。
        </div>
      </section>
    </section>
  </template>
</template>

<style scoped>
/* ==============================
   顶部标杆切换
   ============================== */

.rule-switch-card {
  padding: 0 18px;
  margin-bottom: 14px;
}

.rule-switch-list {
  display: flex;
  min-height: 52px;
  gap: 28px;
  align-items: stretch;
}

.rule-switch-item {
  position: relative;
  padding: 0 4px;
  color: #5f6b7a;
  font-family: inherit;
  font-size: 14px;
  font-weight: 600;
  background: transparent;
  border: 0;
  cursor: pointer;
  transition: color 0.2s ease;
}

.rule-switch-item:hover {
  color: #1f2d3d;
}

.rule-switch-item.active {
  color: #f5223d;
}

.rule-switch-item.active::after {
  position: absolute;
  right: 4px;
  bottom: 0;
  left: 4px;
  height: 2px;
  background: #f5223d;
  content: "";
}

/* ==============================
   主体卡片
   ============================== */

.rule-detail-card {
  min-width: 0;
  padding: 0 20px 20px;
}

/* ==============================
   标杆头部
   ============================== */

.rule-detail-header {
  padding: 20px 0 18px;
  border-bottom: 1px solid #e7edf5;
}

.rule-title-area {
  min-width: 0;
}

.rule-title-area h2 {
  margin: 0;
  color: #1f2d3d;
  font-size: 18px;
  font-weight: 700;
  line-height: 1.5;
}

.rule-description {
  max-width: 900px;
  margin: 8px 0 0;
  color: #41536b;
  font-size: 14px;
  line-height: 1.75;
}

.rule-hint {
  margin: 4px 0 0;
  color: #7b8798;
  font-size: 13px;
  line-height: 1.7;
}

/* ==============================
   内容分区
   ============================== */

.detail-section {
  padding: 20px 0;
  border-bottom: 1px solid #edf1f5;
}

.section-heading {
  margin: 0 0 14px;
  color: #1f2d3d;
  font-size: 15px;
  font-weight: 700;
  line-height: 1.5;
}

/* ==============================
   普通字段展示
   ============================== */

.info-list {
  border-top: 1px solid #edf1f5;
}

.info-row {
  display: grid;
  grid-template-columns:
    120px
    minmax(0, 1fr);
  border-bottom: 1px solid #edf1f5;
}

.info-row:last-child {
  border-bottom: 0;
}

.info-label {
  padding: 12px 14px;
  color: #41536b;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.7;
  background: #f8fafd;
}

.info-value {
  min-width: 0;
  padding: 12px 16px;
  color: #243247;
  font-size: 13px;
  line-height: 1.75;
}

.formula-value {
  font-weight: 500;
}

/* ==============================
   边界说明
   ============================== */

.plain-list {
  padding: 0;
  margin: 0;
  list-style: none;
}

.plain-list li {
  position: relative;
  padding-left: 14px;
  margin-bottom: 5px;
}

.plain-list li:last-child {
  margin-bottom: 0;
}

.plain-list li::before {
  position: absolute;
  top: 10px;
  left: 1px;
  width: 4px;
  height: 4px;
  background: #8a96a8;
  border-radius: 50%;
  content: "";
}

/* ==============================
   计算步骤
   ============================== */

.step-list {
  display: flex;
  width: 100%;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.step-item {
  display: flex;
  min-width: 150px;
  flex: 1;
  gap: 9px;
  align-items: center;
}

.step-number {
  display: inline-flex;
  width: 25px;
  height: 25px;
  flex: 0 0 25px;
  align-items: center;
  justify-content: center;
  color: #41536b;
  font-size: 12px;
  font-weight: 700;
  background: #f2f5f9;
  border: 1px solid #dfe6f0;
  border-radius: 50%;
}

.step-text {
  min-width: 0;
  color: #243247;
  font-size: 13px;
  line-height: 1.6;
}

.step-divider {
  flex: none;
  color: #a5afbf;
  font-size: 16px;
}

/* ==============================
   示例表格
   ============================== */

.example-table {
  max-width: 100%;
  overflow-x: auto;
  overflow-y: hidden;
}

/*
 * ElTable 的表头、边框、行高和 hover
 * 继续使用项目 base.css 的全局样式。
 */

/* ==============================
   最终判定
   ============================== */

.final-section {
  display: grid;
  grid-template-columns:
    120px
    minmax(0, 1fr);
  margin-top: 20px;
  overflow: hidden;
  border: 1px solid #dfe6f0;
  border-radius: 6px;
}

.final-title {
  padding: 13px 14px;
  color: #1f2d3d;
  font-size: 13px;
  font-weight: 700;
  background: #f8fafd;
}

.final-content {
  padding: 13px 16px;
  color: #41536b;
  font-size: 13px;
  line-height: 1.75;
}

/* ==============================
   响应式
   ============================== */

@media (width <= 900px) {
  .step-list {
    align-items: stretch;
    flex-direction: column;
  }

  .step-item {
    width: 100%;
    min-width: 0;
    flex: none;
  }

  .step-divider {
    display: none;
  }
}

@media (width <= 640px) {
  .rule-switch-card {
    padding: 0 14px;
  }

  .rule-switch-list {
    gap: 20px;
    overflow-x: auto;
  }

  .rule-switch-item {
    flex: none;
    white-space: nowrap;
  }

  .rule-detail-card {
    padding: 0 14px 16px;
  }

  .info-row,
  .final-section {
    grid-template-columns: 1fr;
  }

  .info-label,
  .final-title {
    padding-bottom: 6px;
  }

  .info-value,
  .final-content {
    padding-top: 8px;
  }
}
</style>
