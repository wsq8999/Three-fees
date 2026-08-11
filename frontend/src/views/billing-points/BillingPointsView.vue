<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Download, Refresh, Search, Upload } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import type { TableInstance } from "element-plus";

import { businessApi } from "@/api/business-api";
import ExportDataDialog from "@/components/business/ExportDataDialog.vue";
import ImportDataDialog from "@/components/business/ImportDataDialog.vue";
import StatusTag from "@/components/business/StatusTag.vue";
import PageState from "@/components/PageState.vue";
import { parseBillingPointQuery } from "@/router/billing-query-state";
import { useSessionStore } from "@/stores/session";
import type {
  AuditStatus,
  BillingPointDetail,
  BusinessCity,
  PageResult,
} from "@/types/business";

type Summary = BillingPointDetail["summary"];

const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const table = ref<TableInstance>();
const pageData = ref<PageResult<Summary> | null>(null);
const cities = ref<BusinessCity[]>([]);
const loading = ref(false);
const errorMessage = ref("");
const importVisible = ref(false);
const exportVisible = ref(false);
const selectedIds = ref(new Set<string>());

const filters = reactive({
  code: "",
  name: "",
  cityCode: "",
  district: "",
  period: "2026-06",
  siteKeyword: "",
  meterKeyword: "",
  reviewStatus: "",
  pointStatus: "",
  auditStatus: "" as AuditStatus | "",
  reportStatus: "",
  page: 1,
  size: 10,
});

const selectedCount = computed(() => selectedIds.value.size);
const selectedIdList = computed(() => Array.from(selectedIds.value));
const isCityLocked = computed(() => session.currentUser?.city !== null);
const visibleCities = computed(() =>
  session.currentUser?.city ? [session.currentUser.city] : cities.value,
);
const districtOptions = computed(() =>
  Array.from(
    new Set(
      (pageData.value?.items ?? [])
        .map((item) => item.district)
        .filter((value): value is string => Boolean(value)),
    ),
  ),
);
const rangeLabel = computed(() => {
  const total = pageData.value?.totalElements ?? 0;
  if (total === 0) return "已显示 0 条，共 0 条";
  const start = (filters.page - 1) * filters.size + 1;
  const end = Math.min(filters.page * filters.size, total);
  return `已显示 ${start}-${end} 条，共 ${total} 条`;
});

function asSummary(row: unknown): Summary {
  return row as Summary;
}

function queryText(value: unknown): string {
  return Array.isArray(value) ? String(value[0] ?? "") : String(value ?? "");
}

function hydrateFromRoute(): void {
  const base = parseBillingPointQuery(route.query);
  Object.assign(filters, {
    code: queryText(route.query.code),
    name: queryText(route.query.name),
    cityCode: session.currentUser?.city?.code ?? base.cityCode,
    district: queryText(route.query.district),
    period: base.period || "2026-06",
    siteKeyword: queryText(route.query.site),
    meterKeyword: queryText(route.query.meter),
    reviewStatus: queryText(route.query.review),
    pointStatus: queryText(route.query.pointStatus),
    auditStatus: base.auditStatus,
    reportStatus: queryText(route.query.reportStatus),
    page: base.page,
    size: [10, 20, 50].includes(base.size) ? base.size : 10,
  });
  if (route.query.dialog === "import") importVisible.value = true;
}

function routeQuery(): Record<string, string> {
  return {
    ...(filters.code ? { code: filters.code } : {}),
    ...(filters.name ? { name: filters.name } : {}),
    ...(filters.cityCode ? { city: filters.cityCode } : {}),
    ...(filters.district ? { district: filters.district } : {}),
    ...(filters.period ? { period: filters.period } : {}),
    ...(filters.siteKeyword ? { site: filters.siteKeyword } : {}),
    ...(filters.meterKeyword ? { meter: filters.meterKeyword } : {}),
    ...(filters.reviewStatus ? { review: filters.reviewStatus } : {}),
    ...(filters.pointStatus ? { pointStatus: filters.pointStatus } : {}),
    ...(filters.auditStatus ? { status: filters.auditStatus } : {}),
    ...(filters.reportStatus ? { reportStatus: filters.reportStatus } : {}),
    page: String(filters.page),
    size: String(filters.size),
  };
}

function periodText(row: Summary): string {
  return `${row.periodStart ?? `${row.period}-01`} 至 ${row.periodEnd ?? row.period}`;
}

function dayCount(row: Summary): number | null {
  if (!row.periodStart || !row.periodEnd) return null;
  return Math.round((Date.parse(row.periodEnd) - Date.parse(row.periodStart)) / 86400000) + 1;
}

function dailyEnergy(row: Summary): string {
  const days = dayCount(row);
  if (!row.actualEnergy || days === null || days <= 0) return "—";
  return (Number(row.actualEnergy) / days).toFixed(2);
}

function paymentEligibilityText(row: Summary): "APPROVED" | "PENDING" {
  return row.paymentEligibility === "ELIGIBLE" ? "APPROVED" : "PENDING";
}

function billingPointStatusText(row: Summary): string {
  if (row.billingPointStatus === "DISABLED") return "停用";
  return "启用";
}

async function load(): Promise<void> {
  loading.value = true;
  errorMessage.value = "";
  try {
    const keywords = [
      filters.code,
      filters.name,
      filters.siteKeyword,
      filters.meterKeyword,
    ]
      .map((value) => value.trim())
      .filter(Boolean);
    const result = await businessApi.billingPoints.list({
      cityCode: filters.cityCode,
      period: filters.period,
      keyword: keywords[0] ?? "",
      auditStatus: filters.auditStatus,
      page: filters.page,
      size: filters.size,
    });
    result.items = result.items.filter((item) => {
      if (filters.code && !item.code.includes(filters.code.trim())) return false;
      if (filters.name && !item.name.includes(filters.name.trim())) return false;
      if (filters.district && item.district !== filters.district) return false;
      if (filters.reportStatus && item.reportStatus !== filters.reportStatus) return false;
      if (filters.reviewStatus === "APPROVED" && item.paymentEligibility !== "ELIGIBLE") return false;
      if (filters.reviewStatus === "PENDING" && item.paymentEligibility !== "PENDING") return false;
      return true;
    });
    pageData.value = result;
    await nextTick();
    for (const row of result.items) {
      if (selectedIds.value.has(row.id)) {
        table.value?.toggleRowSelection(row, true);
      }
    }
  } catch (error) {
    errorMessage.value =
      error instanceof Error ? error.message : "报账点列表加载失败";
  } finally {
    loading.value = false;
  }
}

async function search(): Promise<void> {
  filters.page = 1;
  await router.replace({ path: "/billing-points", query: routeQuery() });
  await load();
}

async function reset(): Promise<void> {
  Object.assign(filters, {
    code: "",
    name: "",
    cityCode: session.currentUser?.city?.code ?? "",
    district: "",
    period: "2026-06",
    siteKeyword: "",
    meterKeyword: "",
    reviewStatus: "",
    pointStatus: "",
    auditStatus: "",
    reportStatus: "",
    page: 1,
  });
  await search();
}

async function changePage(page: number): Promise<void> {
  filters.page = page;
  await router.replace({ path: "/billing-points", query: routeQuery() });
  await load();
}

async function changeSize(size: number): Promise<void> {
  filters.size = size;
  filters.page = 1;
  await router.replace({ path: "/billing-points", query: routeQuery() });
  await load();
}

function selectionChanged(rows: Summary[]): void {
  const currentIds = new Set(pageData.value?.items.map((item) => item.id) ?? []);
  for (const id of currentIds) selectedIds.value.delete(id);
  for (const row of rows) selectedIds.value.add(row.id);
  selectedIds.value = new Set(selectedIds.value);
}

async function openDetail(row: Summary): Promise<void> {
  await router.push({
    name: "billing-point-detail",
    params: { billingPointCode: row.id, period: row.period },
    query: { from: route.fullPath },
  });
}

async function openDraft(row: Summary): Promise<void> {
  const draft = row.draftId
    ? await businessApi.drafts.get(row.draftId)
    : await businessApi.drafts.createOrResume(row.id);
  if (draft === undefined) {
    ElMessage.error("工作稿不存在或当前账号无权访问。");
    return;
  }
  await router.push({
    name: "report-draft",
    params: { draftId: draft.id },
    query: { from: route.fullPath },
  });
}

async function openReport(row: Summary): Promise<void> {
  const reports = await businessApi.reports.list({
    cityCode: row.city.code,
    district: "",
    period: row.period,
    keyword: row.code,
    source: "",
    page: 1,
    size: 10,
  });
  const report = reports.items.find((item) => item.billingPointCode === row.code);
  if (report === undefined) {
    ElMessage.error("未找到该报账点的正式报告。");
    return;
  }
  await router.push({
    name: "report-detail",
    params: { reportId: report.id },
    query: { from: route.fullPath },
  });
}

function closeImport(visible: boolean): void {
  importVisible.value = visible;
  if (!visible && route.query.dialog === "import") {
    void router.replace({ path: "/billing-points", query: routeQuery() });
  }
}

onMounted(async () => {
  hydrateFromRoute();
  cities.value = await businessApi.cities.list();
  await load();
});

watch(
  () => route.query.dialog,
  (value) => {
    if (value === "import") importVisible.value = true;
  },
);
</script>

<template>
  <section class="filter-card business-card" aria-label="报账点查询">
    <div class="filter-grid">
      <label>
        <span>报账点编码</span>
        <ElInput v-model="filters.code" placeholder="请输入编码" clearable />
      </label>
      <label>
        <span>报账点名称</span>
        <ElInput v-model="filters.name" placeholder="请输入名称" clearable />
      </label>
      <label>
        <span>所属城市</span>
        <ElSelect
          v-model="filters.cityCode"
          :disabled="isCityLocked"
          placeholder="全部城市"
          clearable
        >
          <ElOption
            v-for="city in visibleCities"
            :key="city.code"
            :label="city.name"
            :value="city.code"
          />
        </ElSelect>
      </label>
      <label>
        <span>所属区县</span>
        <ElSelect v-model="filters.district" placeholder="全部区县" clearable>
          <ElOption
            v-for="district in districtOptions"
            :key="district"
            :label="district"
            :value="district"
          />
        </ElSelect>
      </label>
      <label>
        <span>账期</span>
        <ElDatePicker
          v-model="filters.period"
          type="month"
          value-format="YYYY-MM"
          format="YYYY年MM月"
          :clearable="false"
        />
      </label>
      <label>
        <span>站址关键词</span>
        <ElInput
          v-model="filters.siteKeyword"
          placeholder="请输入站址关键词"
          clearable
        />
      </label>
      <label>
        <span>电表关键词</span>
        <ElInput
          v-model="filters.meterKeyword"
          placeholder="请输入电表关键词"
          clearable
        />
      </label>
      <label>
        <span>审核状态</span>
        <ElSelect v-model="filters.reviewStatus" placeholder="全部" clearable>
          <ElOption label="审核通过" value="APPROVED" />
          <ElOption label="待审核" value="PENDING" />
        </ElSelect>
      </label>
      <label>
        <span>报账点状态</span>
        <ElSelect v-model="filters.pointStatus" placeholder="全部" clearable>
          <ElOption label="启用" value="ENABLED" />
          <ElOption label="停用" value="DISABLED" />
        </ElSelect>
      </label>
      <label>
        <span>稽核状态</span>
        <ElSelect v-model="filters.auditStatus" placeholder="全部" clearable>
          <ElOption label="正常" value="NORMAL" />
          <ElOption label="超标" value="OVER_LIMIT" />
          <ElOption label="待审核" value="PENDING_REVIEW" />
          <ElOption label="不适用" value="NOT_APPLICABLE" />
        </ElSelect>
      </label>
      <label>
        <span>报告状态</span>
        <ElSelect v-model="filters.reportStatus" placeholder="全部" clearable>
          <ElOption label="待生成" value="DRAFT" />
          <ElOption label="已生成" value="FINAL" />
          <ElOption label="未生成" value="NONE" />
        </ElSelect>
      </label>
      <div class="filter-actions">
        <ElButton type="primary" :icon="Search" :loading="loading" @click="search">
          查询
        </ElButton>
        <ElButton :icon="Refresh" @click="reset">重置</ElButton>
      </div>
    </div>
  </section>

  <div class="business-toolbar">
    <div>
      <ElButton type="primary" :icon="Upload" @click="importVisible = true">
        导入数据
      </ElButton>
      <ElButton
        :icon="Download"
        :disabled="selectedCount === 0"
        @click="exportVisible = true"
      >
        导出Excel
      </ElButton>
    </div>
    <span>
      <template v-if="selectedCount > 0">
        已选择 <b class="number-emphasis">{{ selectedCount }}</b> 条，
      </template>
      共 <b class="number-emphasis">{{ pageData?.totalElements ?? 0 }}</b> 个报账点
    </span>
  </div>

  <PageState v-if="!pageData && loading" kind="loading" />
  <PageState
    v-else-if="errorMessage"
    kind="error"
    title="报账点列表加载失败"
    :description="errorMessage"
    @retry="load"
  />

  <section v-else class="table-card business-card">
    <ElTable
      ref="table"
      v-loading="loading"
      :data="pageData?.items ?? []"
      row-key="id"
      height="515"
      @selection-change="selectionChanged"
    >
      <ElTableColumn type="selection" width="48" fixed="left" />
      <ElTableColumn prop="code" label="报账点编码" width="205" fixed="left" />
      <ElTableColumn prop="name" label="报账点名称" min-width="180" fixed="left" />
      <ElTableColumn prop="city.name" label="所属地市" width="110" />
      <ElTableColumn label="所属区县" width="110">
        <template #default="scope">{{ asSummary(scope.row).district ?? "—" }}</template>
      </ElTableColumn>
      <ElTableColumn label="缴费单编码" width="190">
        <template #default="scope">{{ asSummary(scope.row).paymentCodes?.[0] ?? "—" }}</template>
      </ElTableColumn>
      <ElTableColumn label="账期" width="190">
        <template #default="scope">{{ periodText(asSummary(scope.row)) }}</template>
      </ElTableColumn>
      <ElTableColumn label="缴费天数" width="95" align="right">
        <template #default="scope">{{ dayCount(asSummary(scope.row)) ?? "—" }}</template>
      </ElTableColumn>
      <ElTableColumn label="日均耗电量" width="120" align="right">
        <template #default="scope">{{ dailyEnergy(asSummary(scope.row)) }}</template>
      </ElTableColumn>
      <ElTableColumn prop="actualEnergy" label="实际总耗电量" width="130" align="right" />
      <ElTableColumn label="实际报账金额" width="135" align="right">
        <template #default="scope">
          ¥{{ asSummary(scope.row).actualAmount ?? "—" }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="审核状态" width="115">
        <template #default="scope">
          <StatusTag :value="paymentEligibilityText(asSummary(scope.row))" />
        </template>
      </ElTableColumn>
      <ElTableColumn label="报账点状态" width="115">
        <template #default="scope">
          <ElTag
            :type="billingPointStatusText(asSummary(scope.row)) === '启用' ? 'success' : 'info'"
            size="small"
          >
            {{ billingPointStatusText(asSummary(scope.row)) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="稽核状态" width="115">
        <template #default="scope">
          <StatusTag :value="asSummary(scope.row).auditStatus" />
        </template>
      </ElTableColumn>
      <ElTableColumn label="超标类型" width="130">
        <template #default="scope">
          <ElTag
            v-if="asSummary(scope.row).auditStatus === 'OVER_LIMIT'"
            type="danger"
            size="small"
          >
            {{ asSummary(scope.row).overLimitType ?? "超标" }}
          </ElTag>
          <span v-else>—</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="报告状态" width="115">
        <template #default="scope">
          <StatusTag :value="asSummary(scope.row).reportStatus" />
        </template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="128" fixed="right">
        <template #default="scope">
          <div class="text-link-actions row-command-actions">
            <ElButton link type="primary" @click="openDetail(asSummary(scope.row))">
              查看
            </ElButton>
            <ElButton
              v-if="asSummary(scope.row).reportStatus === 'DRAFT'"
              link
              type="danger"
              @click="openDraft(asSummary(scope.row))"
            >
              生成报告
            </ElButton>
            <ElButton
              v-if="
                asSummary(scope.row).reportStatus === 'FINAL' ||
                asSummary(scope.row).reportStatus === 'CORRECTED'
              "
              link
              type="success"
              @click="openReport(asSummary(scope.row))"
            >
              查看报告
            </ElButton>
          </div>
        </template>
      </ElTableColumn>
      <template #empty>
        <ElEmpty description="当前条件下没有报账点数据" />
      </template>
    </ElTable>

    <footer class="table-footer">
      <span>{{ rangeLabel }}</span>
      <ElPagination
        background
        layout="sizes, prev, pager, next"
        :current-page="filters.page"
        :page-size="filters.size"
        :page-sizes="[10, 20, 50]"
        :total="pageData?.totalElements ?? 0"
        @current-change="changePage"
        @size-change="changeSize"
      />
    </footer>
  </section>

  <ImportDataDialog
    :model-value="importVisible"
    :default-period="filters.period"
    @update:model-value="closeImport"
    @imported="load"
  />
  <ExportDataDialog
    v-model="exportVisible"
    :scope-label="`当前已选择 ${selectedCount} 条记录`"
    :period="filters.period"
    :city-code="filters.cityCode"
    :billing-point-ids="selectedIdList"
    :selected-count="selectedCount"
  />
</template>

<style scoped>
.filter-card {
  padding: 14px 16px;
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(130px, 1fr));
  gap: 12px 14px;
}

.filter-grid label {
  display: grid;
  min-width: 0;
  gap: 6px;
  color: #1f2d3d;
  font-size: 14px;
  font-weight: 600;
}

.filter-grid :deep(.el-select),
.filter-grid :deep(.el-date-editor),
.filter-grid :deep(.el-input) {
  width: 100%;
}

.filter-actions {
  display: flex;
  gap: 10px;
  align-items: end;
  justify-content: flex-end;
  padding-top: 22px;
}

.filter-actions .el-button {
  min-width: 78px;
}

.table-card {
  overflow: hidden;
}

.table-card :deep(.el-table__empty-block) {
  min-height: 420px;
}

.table-card :deep(td.el-table__fixed-column--right .cell) {
  min-width: 0;
}

.row-command-actions {
  width: 100%;
  justify-content: space-between;
}

.table-footer {
  display: flex;
  min-height: 58px;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  color: #607089;
  border-top: 1px solid #edf1f5;
  font-size: 14px;
}

@media (width <= 1280px) {
  .filter-grid {
    grid-template-columns: repeat(3, minmax(150px, 1fr));
  }
}

@media (width <= 720px) {
  .filter-grid {
    grid-template-columns: 1fr;
  }

  .business-toolbar,
  .table-footer {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
