<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Document, Files, Location, Warning } from "@element-plus/icons-vue";
import type { EChartsType } from "echarts/core";
import { ElMessage } from "element-plus";

import { businessApi } from "@/api/business-api";
import OverLimitRatioTags from "@/components/business/OverLimitRatioTags.vue";
import PageState from "@/components/PageState.vue";
import { useSessionStore } from "@/stores/session";
import type { DashboardData } from "@/types/business";

const router = useRouter();
const route = useRoute();
const session = useSessionStore();
const dashboard = ref<DashboardData | null>(null);
const loading = ref(true);
const errorMessage = ref("");
const selectedPeriod = ref("");
const importGuideVisible = ref(false);
const openingTaskId = ref<string | null>(null);
const statusPieChartElement = ref<HTMLElement | null>(null);
let statusPieChart: EChartsType | undefined;
let statusPieResizeObserver: ResizeObserver | undefined;

const periodLabel = computed(() => {
  const period = selectedPeriod.value || dashboard.value?.currentDataPeriod;
  if (!period) return "暂无当前账期";
  const [year, month] = period.split("-");
  const end = new Date(Number(year), Number(month), 0).getDate();
  return `${period}-01 至 ${period}-${String(end).padStart(2, "0")}`;
});

const periodOptions = computed(() => dashboard.value?.availablePeriods ?? []);
const importGuideStorageKey = computed(() => {
  const userId = session.currentUser?.id ?? session.currentUser?.username ?? "anonymous";
  return `three-fees-import-guide-dismissed:${userId}`;
});

const overRate = computed(() => {
  if (!dashboard.value || dashboard.value.billingPointCount === 0) return "-";
  return `${((dashboard.value.overLimitBillingPointCount / dashboard.value.billingPointCount) * 100).toFixed(2)}%`;
});

const districtRatioChartData = computed(() =>
  [...(dashboard.value?.districtMaxOverLimitRatios ?? [])].sort((left, right) => right.ratio - left.ratio),
);

const overLimitTypeChartData = computed(() => dashboard.value?.overLimitTypeCounts ?? []);

const statusChartData = computed(() => {
  const data = dashboard.value;
  if (!data) return [];
  return [
    { name: "正常", count: data.normalBillingPointCount, color: "#19a873" },
    { name: "超标", count: data.overLimitBillingPointCount, color: "#f02f44" },
    { name: "数据不足", count: data.pendingReviewCount, color: "#d98b00" },
  ];
});
const hasDistrictChart = computed(() => districtRatioChartData.value.some((item) => item.ratio > 0));
const hasOverLimitTypeChart = computed(() => overLimitTypeChartData.value.some((item) => item.count > 0));
const hasStatusChart = computed(() => (dashboard.value?.billingPointCount ?? 0) > 0);
const maxDistrictRatio = computed(() => Math.max(1, ...districtRatioChartData.value.map((item) => item.ratio)));

function asPendingTask(row: unknown): DashboardData["pendingTasks"][number] {
  return row as DashboardData["pendingTasks"][number];
}

function isTaskAiAnalyzing(task: DashboardData["pendingTasks"][number]): boolean {
  return task.draftAnalysisStatus === "AI_ANALYZING";
}

function taskRegion(task: DashboardData["pendingTasks"][number]): string {
  const city = task.cityName ?? "";
  const county = task.county ?? "";
  return [city, county].filter(Boolean).join("-");
}

function formatChartRatio(value: number): string {
  return `${Number(value).toFixed(2)}%`;
}

async function renderStatusPieChart(): Promise<void> {
  if (!hasStatusChart.value) {
    statusPieResizeObserver?.disconnect();
    statusPieResizeObserver = undefined;
    statusPieChart?.dispose();
    statusPieChart = undefined;
    return;
  }
  await nextTick();
  if (statusPieChartElement.value === null) return;
  if (statusPieChart === undefined) {
    const { createAuditChart } = await import("./components/audit-chart-runtime");
    statusPieChart = createAuditChart(statusPieChartElement.value);
    statusPieResizeObserver = new ResizeObserver(() => statusPieChart?.resize());
    statusPieResizeObserver.observe(statusPieChartElement.value);
  }
  statusPieChart.setOption({
    color: statusChartData.value.map((item) => item.color),
    tooltip: {
      trigger: "item",
      formatter: "{b}<br/>数量：{c}<br/>占比：{d}%",
      confine: false,
      appendToBody: true,
      backgroundColor: "#ffffff",
      borderColor: "#d8e0ea",
      borderWidth: 1,
      extraCssText: "box-shadow: 0 8px 20px rgba(23, 36, 58, 0.14); border-radius: 6px;",
      textStyle: {
        color: "#17243a",
        fontSize: 12,
        fontWeight: 600,
      },
    },
    series: [
      {
        name: "当前账期状态",
        type: "pie",
        radius: ["50%", "82%"],
        center: ["50%", "50%"],
        clockwise: true,
        startAngle: 90,
        avoidLabelOverlap: true,
        label: { show: false },
        labelLine: { show: false },
        emphasis: {
          scale: true,
          scaleSize: 4,
          itemStyle: {
            shadowBlur: 10,
            shadowColor: "rgba(25, 42, 70, 0.16)",
          },
        },
        data: statusChartData.value.map((item) => ({
          name: item.name,
          value: item.count,
        })),
      },
    ],
  });
}

async function load(period = selectedPeriod.value): Promise<void> {
  loading.value = true;
  errorMessage.value = "";
  try {
    dashboard.value = await businessApi.dashboard.get(session.currentUser?.city?.code, period || undefined);
    selectedPeriod.value = dashboard.value.currentDataPeriod ?? "";
    maybeShowImportGuide();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "工作台加载失败";
  } finally {
    loading.value = false;
  }
}

function maybeShowImportGuide(): void {
  const user = session.currentUser;
  if (user === null || user.city === null || dashboard.value === null) return;
  if (dashboard.value.billingPointCount > 0) return;
  if (sessionStorage.getItem(importGuideStorageKey.value) === "true") return;
  importGuideVisible.value = true;
}

function closeImportGuide(): void {
  sessionStorage.setItem(importGuideStorageKey.value, "true");
  importGuideVisible.value = false;
}

async function openImportGuideTarget(): Promise<void> {
  closeImportGuide();
  await router.push({ path: "/billing-points", query: { dialog: "import" } });
}

async function changePeriod(): Promise<void> {
  await load(selectedPeriod.value);
}

async function openTask(task: DashboardData["pendingTasks"][number]): Promise<void> {
  if (openingTaskId.value !== null) return;
  if (isTaskAiAnalyzing(task)) {
    ElMessage.info("AI正在后台分析，完成或失败后可继续生成报告。");
    return;
  }
  if (!task.billingPointPeriodId) {
    ElMessage.error("该任务缺少报账点账期信息，请刷新工作台后重试。");
    return;
  }

  openingTaskId.value = task.id;
  try {
    const draft = await businessApi.drafts.createOrResume(task.billingPointPeriodId);
    await router.push({
      name: "report-draft",
      params: { draftId: draft.id },
      query: { from: route.fullPath },
    });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "报告草稿打开失败，请稍后重试。");
  } finally {
    openingTaskId.value = null;
  }
}

watch(
  () => statusChartData.value.map((item) => `${item.name}:${item.count}`).join("|"),
  () => void renderStatusPieChart(),
);

onMounted(async () => {
  await load();
  await renderStatusPieChart();
});

onBeforeUnmount(() => {
  statusPieResizeObserver?.disconnect();
  statusPieChart?.dispose();
});
</script>

<template>
  <PageState v-if="loading" kind="loading" />
  <PageState
    v-else-if="errorMessage"
    kind="error"
    title="工作台加载失败"
    :description="errorMessage"
    @retry="load"
  />

  <div v-else-if="dashboard" class="dashboard-page">
    <section class="stat-grid">
      <article class="stat-card">
        <span class="stat-icon red"><Files /></span>
        <div>
          <h3>当前账期报账点</h3>
          <small>账期：{{ periodLabel }}</small>
        </div>
        <strong>{{ dashboard.billingPointCount }}</strong>
      </article>
      <article class="stat-card">
        <span class="stat-icon blue"><Location /></span>
        <div>
          <h3>当前账期站址</h3>
          <small>清单更新时间：{{ dashboard.lastUpdatedAt?.replace("T", " ").slice(0, 16) ?? "—" }}</small>
        </div>
        <strong>{{ dashboard.siteCount }}</strong>
      </article>
      <article class="stat-card">
        <span class="stat-icon yellow"><Warning /></span>
        <div>
          <h3>超标报账点</h3>
          <small>超标占比：{{ overRate }}</small>
        </div>
        <strong class="orange">{{ dashboard.overLimitBillingPointCount }}</strong>
      </article>
      <article class="stat-card">
        <span class="stat-icon green"><Document /></span>
        <div>
          <h3>待生成报告</h3>
          <small>已生成报告：{{ dashboard.finalReportCount }}份</small>
        </div>
        <strong class="green">{{ dashboard.pendingReportCount }}</strong>
      </article>
    </section>

    <section class="task-card">
      <header>
        <div>
          <h2>待生成报告任务</h2>
        </div>
        <ElSelect
          v-model="selectedPeriod"
          placeholder="暂无账期"
          :disabled="periodOptions.length === 0"
          @change="changePeriod"
        >
          <ElOption
            v-for="period in periodOptions"
            :key="period"
            :label="period === dashboard.currentDataPeriod ? `${period}　当前` : period"
            :value="period"
          />
        </ElSelect>
      </header>
      <div class="task-table-scroll">
        <ElTable
          class="task-table"
          :data="dashboard.pendingTasks"
          height="100%"
          table-layout="auto"
        >
          <ElTableColumn
            prop="billingPointCode"
            label="报账点编码"
            width="210"
          />

          <ElTableColumn
            prop="billingPointName"
            label="报账点名称"
            min-width="220"
          />

          <ElTableColumn
            label="所属区域"
            width="150"
          >
            <template #default="scope">
              {{ taskRegion(asPendingTask(scope.row)) || "—" }}
            </template>
          </ElTableColumn>

          <ElTableColumn
            prop="period"
            label="账期"
            width="98"
          />

          <ElTableColumn
            label="超标比例"
            min-width="230"
          >
            <template #default="scope">
              <OverLimitRatioTags :ratios="scope.row.overLimitRatios" quiet />
            </template>
          </ElTableColumn>

          <ElTableColumn
            label="操作"
            width="104"
            align="center"
          >
            <template #default="scope">
              <ElButton
                link
                type="danger"
                :loading="openingTaskId === scope.row.id"
                :disabled="
          isTaskAiAnalyzing(asPendingTask(scope.row)) ||
          (openingTaskId !== null &&
            openingTaskId !== scope.row.id)
        "
                @click="openTask(asPendingTask(scope.row))"
              >
                {{
                  isTaskAiAnalyzing(asPendingTask(scope.row))
                    ? "AI分析中"
                    : "生成报告"
                }}
              </ElButton>
            </template>
          </ElTableColumn>
          <template #empty>
            <ElEmpty description="当前账期暂无待生成报告" />
          </template>
        </ElTable>
      </div>
    </section>

    <section class="chart-grid">
      <article class="chart-card">
        <h3>各区县最高超标比例</h3>

        <div v-if="hasDistrictChart" class="district-chart-scroll">
          <div
            v-for="(item, index) in districtRatioChartData"
            :key="item.name"
            class="rank-row"
            :title="`${item.name}：${formatChartRatio(item.ratio)}`"
          >
            <em>{{ index + 1 }}</em>
            <span>{{ item.name }}</span>
            <i
              :style="{
                '--bar-width': `${Math.max(8, (item.ratio / maxDistrictRatio) * 100)}%`,
              }"
            />
            <b>{{ formatChartRatio(item.ratio) }}</b>
          </div>
        </div>

        <ElEmpty
          v-else
          class="chart-empty"
          :image-size="42"
          description="当前账期暂无图表数据"
        />
      </article>

      <article class="chart-card">
        <h3>超标类型分布</h3>
        <div v-if="hasOverLimitTypeChart" class="type-dot-chart">
          <div
            v-for="item in overLimitTypeChartData"
            :key="item.name"
            class="type-dot-row"
            :title="`${item.name}：${item.count}`"
          >
            <span>{{ item.name }}</span>
            <i />
            <b>{{ item.count }}</b>
          </div>
        </div>
        <ElEmpty
          v-else
          class="chart-empty"
          :image-size="42"
          description="当前账期暂无图表数据"
        />
      </article>

      <article class="chart-card">
        <h3>当前账期状态分布</h3>
        <div v-if="hasStatusChart" class="status-pie-panel">
          <div class="status-pie-figure">
            <div ref="statusPieChartElement" class="status-pie-chart" />
            <div class="status-pie-center">
              <span>
                <b>{{ dashboard.billingPointCount }}</b>
                <small>总数</small>
              </span>
            </div>
          </div>
          <div class="status-pie-legend">
            <span v-for="item in statusChartData" :key="item.name">
              <i :style="{ background: item.color }" />
              {{ item.name }}
            </span>
          </div>
        </div>
        <ElEmpty
          v-else
          class="chart-empty"
          :image-size="42"
          description="当前账期暂无图表数据"
        />
      </article>
    </section>

    <ElDialog
      v-model="importGuideVisible"
      title="请先导入报账点数据"
      width="min(520px, calc(100vw - 32px))"
      class="import-guide-dialog"
      append-to-body
      align-center
      :close-on-click-modal="false"
      @close="closeImportGuide"
    >
      <p class="import-guide-copy">
        当前城市还没有报账点数据。首次使用请先进入“报账点管理”，从“导入数据”开始导入报账点清单，之后再导入缴费明细、电表读数和标杆值。
      </p>
      <template #footer>
        <ElButton @click="closeImportGuide">稍后再说</ElButton>
        <ElButton type="primary" @click="openImportGuideTarget">去导入数据</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.dashboard-page {
  display: grid;
  height: calc(100dvh - var(--topbar-height) - clamp(24px, 2.7vw, 48px));
  min-height: 0;
  grid-template-rows: auto minmax(0, 1fr) auto;
  gap: 10px;
  overflow: hidden;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 0;
}

.stat-card,
.task-card,
.chart-card {
  background: #fff;
  border: 1px solid #dde5ef;
  border-radius: 12px;
  box-shadow: 0 6px 18px rgb(25 42 70 / 6%);
}

/*
 * 顶部四张统计卡固定为紧凑高度。
 * 小屏也只缩小，不会再被媒体查询改成更高的 124/132px。
 */
.stat-card {
  display: grid;
  height: 88px;
  min-height: 88px;
  grid-template-columns: 34px minmax(0, 1fr) 46px;
  gap: 9px;
  align-items: center;
  padding: 10px 12px;
  overflow: hidden;
  box-sizing: border-box;
}

.stat-card > div {
  min-width: 0;
}

.stat-icon {
  display: grid;
  width: 34px;
  height: 34px;
  flex: none;
  place-items: center;
  border-radius: 10px;
}

.stat-icon svg {
  width: 18px;
}

.stat-icon.red {
  color: #f22940;
  background: #fff0f3;
}

.stat-icon.blue {
  color: #2b7de9;
  background: #edf5ff;
}

.stat-icon.yellow {
  color: #d98b00;
  background: #fff7e8;
}

.stat-icon.green {
  color: #12a876;
  background: #eaf8f2;
}

.stat-card h3,
.stat-card small {
  margin: 0;
}

.stat-card h3 {
  overflow: hidden;
  color: #17243a;
  font-size: 14px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stat-card small {
  display: -webkit-box;
  max-height: 28px;
  margin-top: 3px;
  overflow: hidden;
  color: #66758a;
  font-size: 12px;
  line-height: 15px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow-wrap: anywhere;
}

.stat-card strong {
  display: block;
  width: 46px;
  color: #ef3146;
  font-size: 28px;
  font-weight: 800;
  line-height: 1;
  text-align: right;
  white-space: nowrap;
}

.stat-card strong.orange {
  color: #e89918;
}

.stat-card strong.green {
  color: #16a56f;
}

.task-card {
  display: flex;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  margin-bottom: 0;
}

.task-card > header {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 8px 14px;
  border-bottom: 1px solid #e4eaf2;
}

.task-card h2 {
  margin: 0;
  color: #111d31;
  font-size: 18px;
  line-height: 24px;
}

.task-card p {
  margin-top: 3px;
  color: #66758a;
  font-size: 11px;
}

.task-card :deep(.el-select) {
  width: 210px;
  flex: 0 0 210px;
}

.task-card :deep(.el-select__wrapper) {
  min-height: 34px;
}

.number-emphasis {
  color: #f02f44;
  white-space: nowrap;
}

.task-table-scroll {
  width: 100%;
  min-height: 0;
  flex: 1 1 auto;
  overflow-x: auto;
  overflow-y: hidden;
}

.task-table-scroll::-webkit-scrollbar {
  height: 8px;
}

.task-table-scroll::-webkit-scrollbar-track {
  background: transparent;
}

.task-table-scroll::-webkit-scrollbar-thumb {
  background: #d4dce7;
  border-radius: 999px;
}

.task-table-scroll::-webkit-scrollbar-thumb:hover {
  background: #b8c2d0;
}

.task-table {
  height: 100%;
  min-width: 980px;
}

.task-table :deep(.el-table__cell) {
  padding: 10px 0;
}

.task-table :deep(.el-table),
.task-table :deep(.el-table__inner-wrapper),
.task-table :deep(.el-table__body-wrapper),
.task-table :deep(.el-table__header-wrapper),
.task-table :deep(.el-scrollbar__view) {
  min-width: 980px;
}

.task-table :deep(.el-table__header .cell) {
  padding-right: 8px;
  padding-left: 8px;
  font-size: 14px;
  font-weight: 700;
  white-space: nowrap;
}

.task-table :deep(.el-table__body .cell) {
  padding-right: 8px;
  padding-left: 8px;
  font-size: 14px;
  line-height: 1.45;
  overflow: visible;
  text-overflow: clip;
  white-space: nowrap;
}

.task-table :deep(.el-tag) {
  padding: 0 5px;
  font-size: 12px;
  white-space: nowrap;
}

/*
 * 图表区固定为三列，三张卡固定为相同的紧凑高度。
 * 不允许第一张内容多时把整排卡片一起撑高。
 */
.chart-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  align-items: start;
}

.chart-card {
  display: flex;
  height: 230px !important;
  min-height: 230px !important;
  max-height: 230px;
  min-width: 0;
  flex-direction: column;
  padding: 10px 12px;
  overflow: hidden;
  box-sizing: border-box;
}

.chart-card h3 {
  flex: 0 0 auto;
  margin: 0 0 12px;
  color: #17243a;
  font-size: 16px;
  font-weight: 700;
  line-height: 1.25;
}

/*
 * 第一张图：
 * 条目少时自动平均占满标题下方高度；
 * 条目多时只在卡片内部右侧出现纵向滚动条。
 */
.district-chart-scroll {
  display: grid;
  min-height: 0;
  flex: 1 1 auto;
  grid-auto-rows: minmax(34px, 1fr);
  gap: 8px;
  padding-right: 3px;
  overflow-x: hidden;
  overflow-y: auto;
  scrollbar-gutter: stable;
}

.district-chart-scroll::-webkit-scrollbar {
  width: 5px;
}

.district-chart-scroll::-webkit-scrollbar-track {
  background: transparent;
}

.district-chart-scroll::-webkit-scrollbar-thumb {
  background: #d4dce7;
  border-radius: 999px;
}

.district-chart-scroll::-webkit-scrollbar-thumb:hover {
  background: #b8c2d0;
}

.rank-row {
  display: grid;
  min-width: 0;
  grid-template-columns: 22px minmax(72px, 96px) minmax(0, 1fr) 58px;
  gap: 9px;
  align-items: center;
  padding: 4px 2px;
  color: #23324a;
  font-size: 13px;
}

.rank-row em {
  display: grid;
  width: 18px;
  height: 18px;
  place-items: center;
  color: #9aa8b7;
  font-style: normal;
  font-weight: 700;
  background: #f3f6fa;
  border-radius: 999px;
}

.rank-row span {
  min-width: 0;
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rank-row b {
  color: #17243a;
  font-size: 14px;
  font-weight: 800;
  text-align: right;
  white-space: nowrap;
}

.rank-row i {
  position: relative;
  height: 7px;
  overflow: hidden;
  background: #eef2f7;
  border-radius: 99px;
}

.rank-row i::after {
  position: absolute;
  inset: 0;
  width: var(--bar-width, 0%);
  content: "";
  background: linear-gradient(90deg, #c14453, #f3aab5);
  border-radius: inherit;
}

.type-dot-chart {
  display: grid;
  min-height: 0;
  flex: 1 1 auto;
  gap: 14px;
  align-content: center;
  overflow: hidden;
}

.type-dot-row {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(96px, 136px) minmax(0, 1fr) 28px;
  gap: 10px;
  align-items: center;
}

.type-dot-row span {
  min-width: 0;
  overflow: hidden;
  color: #23324a;
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.type-dot-row i {
  position: relative;
  height: 1px;
  background: #dbe4ef;
}

.type-dot-row i::before {
  position: absolute;
  top: 50%;
  left: 0;
  width: 10px;
  height: 10px;
  content: "";
  background: #3b73c4;
  border: 3px solid #eff8ff;
  border-radius: 999px;
  transform: translateY(-50%);
}

.type-dot-row b {
  color: #17243a;
  font-size: 14px;
  font-weight: 800;
  text-align: right;
}

.status-pie-panel {
  display: grid;
  min-height: 0;
  flex: 1 1 auto;
  width: fit-content;
  max-width: 100%;
  grid-template-columns: 150px max-content;
  gap: 14px;
  align-items: center;
  justify-content: center;
  place-self: center;
  overflow: hidden;
}

.status-pie-figure {
  position: relative;
  width: 150px;
  aspect-ratio: 1;
}

.status-pie-chart {
  width: 100%;
  height: 100%;
}

.status-pie-center {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  pointer-events: none;
}

.status-pie-center span {
  display: grid;
  width: 68px;
  aspect-ratio: 1;
  place-items: center;
  background: #ffffff;
  border-radius: 50%;
  box-shadow: 0 0 0 1px #edf1f6;
}

.status-pie-center b {
  color: #17243a;
  font-size: 22px;
  font-weight: 800;
  line-height: 1;
}

.status-pie-center small {
  margin-top: -12px;
  color: #66758a;
  font-size: 11px;
}

.status-pie-legend {
  display: flex;
  max-width: 100%;
  flex-direction: column;
  gap: 10px;
  align-items: flex-start;
  justify-content: center;
}

.status-pie-legend span {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  color: #42526a;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.status-pie-legend i {
  width: 8px;
  height: 8px;
  border-radius: 99px;
}

.chart-empty {
  min-height: 0;
  flex: 1 1 auto;
}

.chart-empty :deep(.el-empty) {
  height: 100%;
  padding: 0;
}

.chart-empty :deep(.el-empty__description) {
  margin-top: 4px;
  font-size: 12px;
}

.import-guide-copy {
  margin: 0;
  color: #2f3f55;
  font-size: 14px;
  line-height: 1.8;
}

/*
 * 小屏：只继续压缩统计卡和图表内部，不把图表改成单列。
 */
@media (width <= 1280px) {
  .stat-grid {
    gap: 8px;
  }

  .stat-card {
    height: 84px;
    min-height: 84px;
    grid-template-columns: 30px minmax(0, 1fr) 40px;
    gap: 7px;
    padding: 9px 10px;
  }

  .stat-icon {
    width: 30px;
    height: 30px;
  }

  .stat-icon svg {
    width: 16px;
  }

  .stat-card h3 {
    font-size: 12px;
  }

  .stat-card small {
    max-height: 26px;
    font-size: 9px;
    line-height: 13px;
  }

  .stat-card strong {
    width: 40px;
    font-size: 22px;
  }

  .task-card h2 {
    font-size: 16px;
    line-height: 22px;
  }

  .task-table :deep(.el-table__cell) {
    padding: 7px 0;
  }

  .task-table :deep(.el-table__header .cell),
  .task-table :deep(.el-table__body .cell) {
    padding-right: 6px;
    padding-left: 6px;
    font-size: 12px;
  }

  .task-table :deep(.el-table__body .cell) {
    line-height: 1.35;
  }

  .chart-grid {
    gap: 8px;
  }

  .chart-card {
    height: 158px !important;
    min-height: 158px !important;
    max-height: 158px;
    padding: 9px 10px;
  }

  .chart-card h3 {
    margin-bottom: 5px;
    font-size: 15px;
  }

  .rank-row {
    grid-template-columns: 22px minmax(48px, 64px) minmax(0, 1fr) 48px;
    gap: 6px;
    padding: 3px 1px;
    font-size: 13px;
  }

  .rank-row em {
    width: 19px;
    height: 19px;
    border-radius: 7px;
  }

  .rank-row b {
    font-size: 13px;
  }

  .type-dot-chart {
    gap: 9px;
  }

  .type-dot-row {
    grid-template-columns: minmax(74px, 104px) minmax(0, 1fr) 22px;
    gap: 7px;
  }

  .type-dot-row span {
    font-size: 12px;
  }

  .type-dot-row b {
    font-size: 13px;
  }

  .status-pie-panel {
    grid-template-columns: 102px max-content;
    gap: 10px;
  }

  .status-pie-figure {
    width: 102px;
  }

  .status-pie-center span {
    width: 48px;
  }

  .status-pie-center b {
    font-size: 16px;
  }

  .status-pie-center small {
    margin-top: -10px;
    font-size: 10px;
  }

  .status-pie-legend {
    gap: 7px;
  }

  .status-pie-legend span {
    font-size: 11px;
  }

  .status-pie-legend i {
    width: 7px;
    height: 7px;
  }
}

@media (width <= 760px) {
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .stat-card {
    height: 82px;
    min-height: 82px;
  }

  .chart-card {
    height: 150px !important;
    min-height: 150px !important;
    max-height: 150px;
    padding: 8px;
  }
}

@media (height <= 760px) {
  .dashboard-page {
    gap: 8px;
  }

  .stat-card {
    height: 78px;
    min-height: 78px;
    padding-top: 8px;
    padding-bottom: 8px;
  }

  .task-card > header {
    padding-top: 6px;
    padding-bottom: 6px;
  }

  .task-table :deep(.el-table__cell) {
    padding: 6px 0;
  }

  .chart-card {
    height: 142px !important;
    min-height: 142px !important;
    max-height: 142px;
    padding: 8px 10px;
  }

  .chart-card h3 {
    margin-bottom: 4px;
    font-size: 14px;
  }

  .status-pie-panel {
    grid-template-columns: 92px max-content;
    gap: 8px;
  }

  .status-pie-figure {
    width: 92px;
  }
}

@media (height <= 680px) {
  .stat-card {
    height: 72px;
    min-height: 72px;
  }

  .stat-icon {
    width: 28px;
    height: 28px;
  }

  .stat-card strong {
    font-size: 20px;
  }

  .chart-card {
    height: 126px !important;
    min-height: 126px !important;
    max-height: 126px;
  }
}
</style>
