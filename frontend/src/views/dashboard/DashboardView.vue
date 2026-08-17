<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Document, Files, Location, Warning } from "@element-plus/icons-vue";

import { businessApi, formatPercent } from "@/api/business-api";
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

const districtChartData = computed(() =>
  [...(dashboard.value?.districtOverLimitCounts ?? [])].sort((left, right) => right.count - left.count),
);

const overLimitTypeChartData = computed(() => dashboard.value?.overLimitTypeCounts ?? []);

const statusChartData = computed(() => {
  const data = dashboard.value;
  if (!data) return [];
  return [
    { name: "正常", count: data.normalBillingPointCount, color: "#19a873" },
    { name: "超标", count: data.overLimitBillingPointCount, color: "#f02f44" },
    { name: "待稽核/不适用", count: data.pendingReviewCount, color: "#d98b00" },
  ];
});

const hasDistrictChart = computed(() => districtChartData.value.some((item) => item.count > 0));
const hasOverLimitTypeChart = computed(() => overLimitTypeChartData.value.some((item) => item.count > 0));
const hasStatusChart = computed(() => (dashboard.value?.billingPointCount ?? 0) > 0);
const maxDistrictCount = computed(() => Math.max(1, ...districtChartData.value.map((item) => item.count)));
const maxTypeCount = computed(() => Math.max(1, ...overLimitTypeChartData.value.map((item) => item.count)));
const maxStatusCount = computed(() => Math.max(1, ...statusChartData.value.map((item) => item.count)));

function asPendingTask(row: unknown): DashboardData["pendingTasks"][number] {
  return row as DashboardData["pendingTasks"][number];
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
  if (task.billingPointPeriodId) {
    const draft = await businessApi.drafts.createOrResume(task.billingPointPeriodId);
    await router.push({
      name: "report-draft",
      params: { draftId: draft.id },
      query: { from: route.fullPath },
    });
    return;
  }
  await router.push({
    path: task.target,
  });
}

onMounted(load);
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

  <template v-else-if="dashboard">
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
          <small>超标率：{{ overRate }}</small>
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
          <p>数据来源：当前权限范围 + 当前账期 + 稽核超标 + 报告状态待生成</p>
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
      <ElTable :data="dashboard.pendingTasks" height="310">
        <ElTableColumn prop="billingPointCode" label="报账点编码" min-width="190" />
        <ElTableColumn prop="billingPointName" label="报账点名称" min-width="210" />
        <ElTableColumn prop="county" label="所属区县" width="120" />
        <ElTableColumn prop="period" label="账期" width="110" />
        <ElTableColumn label="实际报账金额" width="135">
          <template #default="scope">¥{{ scope.row.actualAmount }}</template>
        </ElTableColumn>
        <ElTableColumn label="超标类型" width="150">
          <template #default="scope">
            <ElTag type="danger" size="small">{{ scope.row.overLimitType }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="最大超标比例" width="130">
          <template #default="scope">
            <b class="number-emphasis">{{ formatPercent(scope.row.maximumRatio) }}</b>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="105">
          <template #default="scope">
            <ElButton link type="danger" @click="openTask(asPendingTask(scope.row))">生成报告</ElButton>
          </template>
        </ElTableColumn>
        <template #empty>
          <ElEmpty description="当前账期暂无待生成报告" />
        </template>
      </ElTable>
    </section>

    <section class="chart-grid">
      <article class="chart-card">
        <h3>各区县超标报账点数量</h3>
        <div
          v-for="(item, index) in districtChartData"
          :key="item.name"
          class="bar-row"
          :title="`${item.name}：${item.count}`"
        >
          <span>{{ item.name }}</span>
          <i
            :style="{
              width: `${Math.max(8, (item.count / maxDistrictCount) * 100)}%`,
              opacity: String(Math.max(0.35, 1 - index * 0.1)),
            }"
          />
          <b>{{ item.count }}</b>
        </div>
        <ElEmpty v-if="!hasDistrictChart" :image-size="50" description="当前账期暂无图表数据" />
      </article>

      <article class="chart-card">
        <h3>超标类型分布</h3>
        <div v-if="hasOverLimitTypeChart" class="columns">
          <span v-for="item in overLimitTypeChartData" :key="item.name" :title="`${item.name}：${item.count}`">
            <b>{{ item.count }}</b>
            <i :style="{ height: `${Math.max(8, (item.count / maxTypeCount) * 92)}px` }" />
            <small>{{ item.name }}</small>
          </span>
        </div>
        <ElEmpty v-else :image-size="50" description="当前账期暂无图表数据" />
      </article>

      <article class="chart-card">
        <h3>当前账期状态分布</h3>
        <div v-if="hasStatusChart" class="status-bars">
          <div v-for="item in statusChartData" :key="item.name" class="status-row">
            <span>{{ item.name }}</span>
            <i :style="{ width: `${Math.max(4, (item.count / maxStatusCount) * 100)}%`, background: item.color }" />
            <b>{{ item.count }}</b>
          </div>
          <p>合计：{{ dashboard.billingPointCount }} 个报账点</p>
        </div>
        <ElEmpty v-else :image-size="50" description="当前账期暂无图表数据" />
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
  </template>
</template>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

.stat-card,
.task-card,
.chart-card {
  background: #fff;
  border: 1px solid #dde5ef;
  border-radius: 12px;
  box-shadow: 0 6px 18px rgb(25 42 70 / 6%);
}

.stat-card {
  display: grid;
  min-height: 82px;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 14px;
  align-items: center;
  padding: 18px 20px;
}

.stat-icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 11px;
}

.stat-icon svg {
  width: 22px;
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
  color: #17243a;
  font-size: 15px;
  font-weight: 700;
}

.stat-card small {
  color: #66758a;
  font-size: 12px;
  overflow-wrap: anywhere;
}

.stat-card strong {
  color: #ef3146;
  font-size: 30px;
  font-weight: 800;
  line-height: 1;
}

.stat-card strong.orange {
  color: #e89918;
}

.stat-card strong.green {
  color: #16a56f;
}

.task-card {
  overflow: hidden;
  min-height: 388px;
  margin-bottom: 14px;
}

.task-card > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  border-bottom: 1px solid #e4eaf2;
}

.task-card h2,
.task-card p {
  margin: 0;
}

.task-card h2 {
  color: #111d31;
  font-size: 18px;
}

.task-card p {
  color: #66758a;
  font-size: 12px;
}

.task-card :deep(.el-select) {
  width: 252px;
}

.number-emphasis {
  color: #f02f44;
}

.chart-grid {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) minmax(300px, 0.9fr) minmax(320px, 1fr);
  gap: 14px;
}

.chart-card {
  min-height: 180px;
  padding: 14px 16px;
  overflow: hidden;
}

.chart-card h3 {
  margin: 0 0 12px;
  color: #17243a;
  font-size: 15px;
}

.bar-row,
.status-row {
  display: grid;
  grid-template-columns: minmax(72px, 96px) minmax(0, 1fr) 32px;
  gap: 10px;
  align-items: center;
  margin: 9px 0;
  color: #23324a;
  font-size: 13px;
}

.bar-row span,
.status-row span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar-row i,
.status-row i {
  height: 7px;
  border-radius: 99px;
}

.bar-row i {
  background: linear-gradient(90deg, #ee3145, #f79aa5);
}

.columns {
  display: grid;
  min-height: 132px;
  grid-template-columns: repeat(auto-fit, minmax(72px, 1fr));
  gap: 12px;
  align-items: end;
  overflow: hidden;
}

.columns span {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: center;
  color: #23324a;
}

.columns i {
  width: 36px;
  background: linear-gradient(180deg, #f9a3ad, #ef3146);
  border-radius: 9px 9px 0 0;
}

.columns small {
  margin-top: 8px;
  color: #66758a;
  line-height: 1.3;
  text-align: center;
  white-space: normal;
  word-break: break-word;
}

.status-bars p {
  margin: 14px 0 0;
  color: #66758a;
  font-size: 12px;
}

.import-guide-copy {
  margin: 0;
  color: #2f3f55;
  font-size: 14px;
  line-height: 1.8;
}

@media (width <= 1100px) {
  .stat-grid {
    grid-template-columns: 1fr 1fr;
  }

  .chart-grid {
    grid-template-columns: 1fr;
  }
}

@media (width <= 760px) {
  .stat-grid {
    grid-template-columns: 1fr;
  }

  .task-card > header {
    align-items: flex-start;
    flex-direction: column;
  }

  .task-card :deep(.el-select) {
    width: 100%;
  }
}
</style>
