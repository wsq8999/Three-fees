<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { Document, Files, Location, Warning } from "@element-plus/icons-vue";

import { businessApi } from "@/api/business-api";
import PageState from "@/components/PageState.vue";
import { useSessionStore } from "@/stores/session";
import type { DashboardData } from "@/types/business";

const router = useRouter();
const session = useSessionStore();
const dashboard = ref<DashboardData | null>(null);
const loading = ref(true);
const errorMessage = ref("");
const selectedPeriod = ref("");

const periodLabel = computed(() => {
  const period = selectedPeriod.value || dashboard.value?.currentDataPeriod;
  if (!period) return "暂无当前账期";
  const [year, month] = period.split("-");
  const end = new Date(Number(year), Number(month), 0).getDate();
  return `${period}-01 至 ${period}-${String(end).padStart(2, "0")}`;
});

const periodOptions = computed(() => dashboard.value?.availablePeriods ?? []);

const overRate = computed(() => {
  if (!dashboard.value || dashboard.value.billingPointCount === 0) return "—";
  return `${((dashboard.value.overLimitBillingPointCount / dashboard.value.billingPointCount) * 100).toFixed(2)}%`;
});

const normalRate = computed(() => {
  if (!dashboard.value || dashboard.value.billingPointCount === 0) return 0;
  return Math.round(
    (dashboard.value.normalBillingPointCount /
      dashboard.value.billingPointCount) *
      100,
  );
});

const districtChartData = computed(() =>
  [...(dashboard.value?.districtOverLimitCounts ?? [])].sort(
    (left, right) => right.count - left.count,
  ),
);

const overLimitTypeChartData = computed(() => {
  const source = new Map(
    (dashboard.value?.overLimitTypeCounts ?? []).map((item) => [item.name, item.count]),
  );
  return [
    { name: "仅同比", count: source.get("仅同比") ?? source.get("同比超标") ?? 0 },
    { name: "仅环比", count: source.get("仅环比") ?? source.get("环比超标") ?? 0 },
    {
      name: "仅额定功率",
      count:
        source.get("仅额定功率") ??
        source.get("额定标杆超标") ??
        source.get("额定功率超标") ??
        0,
    },
    { name: "多项超标", count: source.get("多项超标") ?? 0 },
  ];
});

const hasDistrictChart = computed(() =>
  districtChartData.value.some((item) => item.count > 0),
);
const hasOverLimitTypeChart = computed(() =>
  overLimitTypeChartData.value.some((item) => item.count > 0),
);
const hasStatusChart = computed(() => (dashboard.value?.billingPointCount ?? 0) > 0);
const maxDistrictCount = computed(() =>
  Math.max(1, ...districtChartData.value.map((item) => item.count)),
);
const maxTypeCount = computed(() =>
  Math.max(1, ...overLimitTypeChartData.value.map((item) => item.count)),
);

function asPendingTask(row: unknown): DashboardData["pendingTasks"][number] {
  return row as DashboardData["pendingTasks"][number];
}

async function load(period = selectedPeriod.value): Promise<void> {
  loading.value = true;
  errorMessage.value = "";
  try {
    dashboard.value = await businessApi.dashboard.get(
      session.currentUser?.city?.code,
      period || undefined,
    );
    selectedPeriod.value = dashboard.value.currentDataPeriod ?? "";
  } catch (error) {
    errorMessage.value =
      error instanceof Error ? error.message : "工作台加载失败";
  } finally {
    loading.value = false;
  }
}

async function changePeriod(): Promise<void> {
  await load(selectedPeriod.value);
}

async function openTask(task: DashboardData["pendingTasks"][number]): Promise<void> {
  await router.push({
    path: "/reports/generate",
    query: {
      billingPointCode: task.billingPointCode,
      billingPointName: task.billingPointName,
      period: task.period,
    },
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
          <small
            >清单更新时间：{{
              dashboard.lastUpdatedAt?.replace("T", " ").slice(0, 16) ?? "—"
            }}</small
          >
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
        <strong class="green">{{ dashboard.draftReportCount }}</strong>
      </article>
    </section>

    <section class="task-card">
      <header>
        <div>
          <h2>待生成报告任务</h2>
          <p>按最大超标比例排序 · 当前仅展示有权限的数据</p>
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
        <ElTableColumn prop="county" label="所属区县" width="110" />
        <ElTableColumn prop="period" label="账期" width="190" />
        <ElTableColumn label="实际报账金额" width="135">
          <template #default="scope">¥{{ scope.row.actualAmount }}</template>
        </ElTableColumn>
        <ElTableColumn label="超标类型" width="120">
          <template #default="scope">
            <ElTag type="danger" size="small">{{ scope.row.overLimitType }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="最大超标比例" width="130">
          <template #default="scope">
            <b class="number-emphasis">{{ scope.row.maximumRatio }}</b>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="105">
          <template #default="scope">
            <ElButton link type="danger" @click="openTask(asPendingTask(scope.row))">
              生成报告
            </ElButton>
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
              opacity: String(1 - index * 0.12),
            }"
          />
          <b>{{ item.count }}</b>
        </div>
        <ElEmpty
          v-if="!hasDistrictChart"
          :image-size="50"
          description="当前账期暂无图表数据"
        />
      </article>
      <article class="chart-card">
        <h3>超标类型分布</h3>
        <div v-if="hasOverLimitTypeChart" class="columns">
          <span
            v-for="item in overLimitTypeChartData"
            :key="item.name"
            :title="`${item.name}：${item.count}`"
          >
            <b>{{ item.count }}</b>
            <i :style="{ height: `${Math.max(8, (item.count / maxTypeCount) * 92)}px` }" />
            <small>{{ item.name }}</small>
          </span>
        </div>
        <ElEmpty
          v-else
          :image-size="50"
          description="当前账期暂无图表数据"
        />
      </article>
      <article class="chart-card">
        <h3>当前账期状态分布</h3>
        <div v-if="hasStatusChart" class="donut-wrap">
          <div class="donut" :style="{ '--normal-rate': `${normalRate * 3.6}deg` }">
            <strong>{{ dashboard.billingPointCount }}</strong>
            <small>报账点</small>
          </div>
          <dl>
            <div><dt class="green-dot" />正常</div>
            <dd>{{ dashboard.normalBillingPointCount }}</dd>
            <div><dt class="red-dot" />超标</div>
            <dd>{{ dashboard.overLimitBillingPointCount }}</dd>
          </dl>
        </div>
        <ElEmpty
          v-else
          :image-size="50"
          description="当前账期暂无图表数据"
        />
      </article>
    </section>
  </template>
</template>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
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
  grid-template-columns: auto 1fr auto;
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
  margin-bottom: 14px;
  min-height: 388px;
}

.task-card > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
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

.chart-grid {
  display: grid;
  grid-template-columns: 1.05fr 0.95fr 1fr;
  gap: 14px;
}

.chart-card {
  min-height: 170px;
  padding: 14px 16px;
}

.chart-card h3 {
  margin: 0 0 12px;
  color: #17243a;
  font-size: 15px;
}

.bar-row {
  display: grid;
  grid-template-columns: 60px 1fr 24px;
  gap: 12px;
  align-items: center;
  margin: 9px 0;
  color: #23324a;
  font-size: 13px;
}

.bar-row i {
  height: 7px;
  background: linear-gradient(90deg, #ee3145, #f79aa5);
  border-radius: 99px;
}

.columns {
  display: flex;
  height: 112px;
  gap: 24px;
  align-items: end;
  justify-content: center;
}

.columns span {
  display: flex;
  flex: 1;
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
}

.donut-wrap {
  display: flex;
  gap: 28px;
  align-items: center;
  justify-content: center;
  min-height: 112px;
}

.donut {
  position: relative;
  display: grid;
  width: 108px;
  height: 108px;
  place-content: center;
  text-align: center;
  background: conic-gradient(#19a873 0 var(--normal-rate), #f02f44 var(--normal-rate) 360deg);
  border-radius: 50%;
}

.donut::after {
  position: absolute;
  inset: 17px;
  content: "";
  background: white;
  border-radius: 50%;
}

.donut strong,
.donut small {
  position: relative;
  z-index: 1;
}

.donut strong {
  color: #20304a;
  font-size: 22px;
}

.donut small {
  color: #66758a;
}

.donut-wrap dl {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px 14px;
  margin: 0;
}

.donut-wrap dl > div {
  display: flex;
  gap: 8px;
  align-items: center;
}

.donut-wrap dd {
  margin: 0;
  font-weight: 700;
}

.green-dot,
.red-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.green-dot {
  background: #19a873;
}

.red-dot {
  background: #f02f44;
}

@media (width <= 1100px) {
  .stat-grid {
    grid-template-columns: 1fr 1fr;
  }

  .chart-grid {
    grid-template-columns: 1fr;
  }
}
</style>
