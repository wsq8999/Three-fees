<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Document, Files, Location, Warning } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";

import { businessApi } from "@/api/business-api";
import OverLimitRatioTags from "@/components/business/OverLimitRatioTags.vue";
import OverLimitTypeTags from "@/components/business/OverLimitTypeTags.vue";
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
    { name: "数据不足", count: data.pendingReviewCount, color: "#d98b00" },
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

function isTaskAiAnalyzing(task: DashboardData["pendingTasks"][number]): boolean {
  return task.draftAnalysisStatus === "AI_ANALYZING";
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
      <div class="task-table-scroll">
        <ElTable
          class="task-table"
          :data="dashboard.pendingTasks"
          height="310"
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
            prop="county"
            label="所属区县"
            width="100"
          />

          <ElTableColumn
            prop="period"
            label="账期"
            width="98"
          />

          <ElTableColumn
            label="实际报账金额"
            width="124"
            align="right"
          >
            <template #default="scope">
              ¥{{ scope.row.actualAmount }}
            </template>
          </ElTableColumn>

          <ElTableColumn
            label="超标类型"
            min-width="150"
            align="center"
          >
            <template #default="scope">
              <OverLimitTypeTags
                :ratios="scope.row.overLimitRatios"
                :fallback="scope.row.overLimitType"
              />
            </template>
          </ElTableColumn>

          <ElTableColumn
            label="超标比例"
            min-width="230"
          >
            <template #default="scope">
              <OverLimitRatioTags :ratios="scope.row.overLimitRatios" />
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
        <h3>各区县超标报账点数量</h3>

        <div v-if="hasDistrictChart" class="district-chart-scroll">
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
        <div v-if="hasOverLimitTypeChart" class="columns">
          <span
            v-for="item in overLimitTypeChartData"
            :key="item.name"
            :title="`${item.name}：${item.count}`"
          >
            <b>{{ item.count }}</b>
            <i
              :style="{
                height: `${Math.max(8, (item.count / maxTypeCount) * 52)}px`,
              }"
            />
            <small>{{ item.name }}</small>
          </span>
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
        <div v-if="hasStatusChart" class="status-bars">
          <div v-for="item in statusChartData" :key="item.name" class="status-row">
            <span>{{ item.name }}</span>
            <i :style="{ width: `${Math.max(4, (item.count / maxStatusCount) * 100)}%`, background: item.color }" />
            <b>{{ item.count }}</b>
          </div>
          <p>合计：{{ dashboard.billingPointCount }} 个报账点</p>
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
  </template>
</template>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
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
  font-size: 13px;
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
  font-size: 10px;
  line-height: 14px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow-wrap: anywhere;
}

.stat-card strong {
  display: block;
  width: 46px;
  color: #ef3146;
  font-size: 24px;
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
  overflow: visible;
  min-height: 388px;
  margin-bottom: 14px;
}

.task-card > header {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid #e4eaf2;
}

.task-card h2 {
  color: #111d31;
  font-size: 17px;
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

.number-emphasis {
  color: #f02f44;
  white-space: nowrap;
}

.task-table-scroll {
  width: 100%;
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
  min-width: 980px;
}

.task-table :deep(.el-table__cell) {
  padding: 7px 0;
}

.task-table :deep(.el-table),
.task-table :deep(.el-table__inner-wrapper),
.task-table :deep(.el-table__body-wrapper),
.task-table :deep(.el-table__header-wrapper),
.task-table :deep(.el-scrollbar__view) {
  min-width: 980px;
}

.task-table :deep(.el-table__header .cell) {
  padding-right: 6px;
  padding-left: 6px;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
}

.task-table :deep(.el-table__body .cell) {
  padding-right: 6px;
  padding-left: 6px;
  font-size: 12px;
  line-height: 1.35;
  overflow: visible;
  text-overflow: clip;
  white-space: nowrap;
}

.task-table :deep(.el-tag) {
  padding: 0 5px;
  font-size: 11px;
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
  height: 168px !important;
  min-height: 168px !important;
  max-height: 168px;
  min-width: 0;
  flex-direction: column;
  padding: 10px 12px;
  overflow: hidden;
  box-sizing: border-box;
}

.chart-card h3 {
  flex: 0 0 auto;
  margin: 0 0 6px;
  color: #17243a;
  font-size: 15px;
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
  grid-auto-rows: minmax(20px, 1fr);
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

.bar-row,
.status-row {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(48px, 68px) minmax(0, 1fr) 22px;
  gap: 5px;
  align-items: center;
  color: #23324a;
  font-size: 13px;
}

.bar-row {
  min-height: 20px;
  margin: 0;
}

.bar-row span,
.status-row span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar-row b,
.status-row b {
  font-size: 13px;
  text-align: right;
  white-space: nowrap;
}

.bar-row i,
.status-row i {
  height: 5px;
  border-radius: 99px;
}

.bar-row i {
  background: linear-gradient(90deg, #ee3145, #f79aa5);
}

/*
 * 第二张图：
 * 4种类型横向完全等分；
 * 每一项纵向使用“数字 / 柱体区 / 标签”三段，
 * 内容从上到下真正占满图表主体，不再只堆在底部。
 */
.columns {
  display: grid;
  min-height: 0;
  flex: 1 1 auto;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 5px;
  align-items: stretch;
  overflow: hidden;
}

.columns span {
  display: grid;
  min-width: 0;
  height: 100%;
  grid-template-rows: 16px minmax(0, 1fr) 28px;
  align-items: end;
  justify-items: center;
  color: #23324a;
}

.columns b {
  align-self: start;
  margin: 0;
  font-size: 13px;
  line-height: 16px;
}

.columns i {
  width: 22px;
  max-height: 52px;
  align-self: end;
  background: linear-gradient(180deg, #f9a3ad, #ef3146);
  border-radius: 6px 6px 0 0;
}

.columns small {
  display: -webkit-box;
  width: 100%;
  min-height: 28px;
  margin: 0;
  overflow: hidden;
  align-self: end;
  color: #66758a;
  font-size: 11px;
  line-height: 14px;
  text-align: center;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  word-break: break-word;
}

/*
 * 第三张图：
 * 正常 / 超标 / 待稽核 / 合计 4行等分整个可用高度。
 * 这样不会全部挤在上半部分，下方留下大块空白。
 */
.status-bars {
  display: grid;
  min-height: 0;
  flex: 1 1 auto;
  grid-template-rows: repeat(4, minmax(0, 1fr));
  align-items: stretch;
}

.status-row {
  min-height: 0;
  margin: 0;
  align-self: center;
}

.status-bars p {
  margin: 0;
  align-self: center;
  color: #66758a;
  font-size: 12px;
  line-height: 1.3;
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

  .bar-row,
  .status-row {
    grid-template-columns: minmax(42px, 58px) minmax(0, 1fr) 20px;
    gap: 4px;
    font-size: 13px;
  }

  .bar-row b,
  .status-row b {
    font-size: 13px;
  }

  .columns {
    gap: 4px;
  }

  .columns span {
    grid-template-rows: 17px minmax(0, 1fr) 28px;
  }

  .columns b {
    font-size: 13px;
  }

  .columns i {
    width: 20px;
  }

  .columns small {
    min-height: 28px;
    font-size: 11px;
    line-height: 14px;
  }

  .status-bars p {
    font-size: 12px;
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
</style>
