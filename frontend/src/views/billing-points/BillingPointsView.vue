<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Download, Refresh, Search, Upload } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import type { TableInstance } from "element-plus";

import { businessApi } from "@/api/business-api";
import ExportDataDialog from "@/components/business/ExportDataDialog.vue";
import ImportDataDialog from "@/components/business/ImportDataDialog.vue";
import OverLimitRatioTags from "@/components/business/OverLimitRatioTags.vue";
import StatusTag from "@/components/business/StatusTag.vue";
import PageState from "@/components/PageState.vue";
import { parseBillingPointQuery } from "@/router/billing-query-state";
import { useSessionStore } from "@/stores/session";
import type {
  AuditStatus,
  BillingPointDetail,
  BusinessCity,
  ImportBatch,
  PageResult,
  ReportStatus,
} from "@/types/business";

type Summary = BillingPointDetail["summary"];

const route = useRoute();
const router = useRouter();
const session = useSessionStore();

const table = ref<TableInstance>();
const pageData = ref<PageResult<Summary> | null>(null);
const cities = ref<BusinessCity[]>([]);
const filterOptions = ref({ periods: [] as string[], cities: [] as BusinessCity[], districts: [] as string[] });
const loading = ref(false);
const errorMessage = ref("");
const importVisible = ref(false);
const exportVisible = ref(false);

/**
 * 报账点查询区是否收起。
 * false = 默认展开
 * true = 收起
 */
const filterCollapsed = ref(false);

const selectedIds = ref(new Set<string>());

const filters = reactive({
  code: "",
  name: "",
  cityCode: "",
  district: "",
  period: "",
  reviewStatus: "",
  pointStatus: "",
  auditStatus: "" as AuditStatus | "",
  reportStatus: "" as ReportStatus | "",
  focusPeriod: "",
  focusCityCode: "",
  page: 1,
  size: 10,
});

const selectedCount = computed(() => selectedIds.value.size);

const selectedIdList = computed(() =>
  Array.from(selectedIds.value),
);

function isAiAnalyzing(row: Summary): boolean {
  return row.draftAnalysisStatus === "AI_ANALYZING";
}

const isCityLocked = computed(
  () => session.currentUser?.city !== null,
);

const visibleCities = computed(() =>
  session.currentUser?.city
    ? [session.currentUser.city]
    : cities.value,
);

const districtOptions = computed(() => filterOptions.value.districts);

const rangeLabel = computed(() => {
  const total = pageData.value?.totalElements ?? 0;

  if (total === 0) {
    return "已显示 0 条，共 0 条";
  }

  const start =
    (filters.page - 1) * filters.size + 1;

  const end = Math.min(
    filters.page * filters.size,
    total,
  );

  return `已显示 ${start}-${end} 条，共 ${total} 条`;
});

function asSummary(row: unknown): Summary {
  return row as Summary;
}

function queryText(value: unknown): string {
  return Array.isArray(value)
    ? String(value[0] ?? "")
    : String(value ?? "");
}

function hydrateFromRoute(): void {
  const base = parseBillingPointQuery(route.query);

  Object.assign(filters, {
    code: queryText(route.query.code),
    name: queryText(route.query.name),
    cityCode:
      session.currentUser?.city?.code ??
      base.cityCode,
    district: queryText(route.query.district),
    period: base.period,
    reviewStatus: queryText(route.query.review),
    pointStatus: queryText(route.query.pointStatus),
    auditStatus: base.auditStatus,
    reportStatus: queryText(route.query.reportStatus),
    focusPeriod: queryText(route.query.focusPeriod),
    focusCityCode: queryText(route.query.focusCity),
    page: base.page,

    /*
     * 报账点管理默认每页固定 10 条。
     * 进入页面时不再继承 URL 中遗留的 size=20/50。
     * 用户进入页面后仍可通过分页器手动切换 20/50 条。
     */
    size: 10,
  });

  if (route.query.dialog === "import") {
    importVisible.value = true;
  }
}

function routeQuery(): Record<string, string> {
  return {
    ...(filters.code
      ? { code: filters.code }
      : {}),
    ...(filters.name
      ? { name: filters.name }
      : {}),
    ...(filters.cityCode
      ? { city: filters.cityCode }
      : {}),
    ...(filters.district
      ? { district: filters.district }
      : {}),
    ...(filters.period
      ? { period: filters.period }
      : {}),
    ...(filters.reviewStatus
      ? { review: filters.reviewStatus }
      : {}),
    ...(filters.pointStatus
      ? { pointStatus: filters.pointStatus }
      : {}),
    ...(filters.auditStatus
      ? { status: filters.auditStatus }
      : {}),
    ...(filters.reportStatus
      ? { reportStatus: filters.reportStatus }
      : {}),
    ...(filters.focusPeriod
      ? { focusPeriod: filters.focusPeriod }
      : {}),
    ...(filters.focusCityCode
      ? { focusCity: filters.focusCityCode }
      : {}),
    page: String(filters.page),
    size: String(filters.size),
  };
}

function periodText(row: Summary): string {
  return `${
    row.periodStart ?? `${row.period}-01`
  } 至 ${row.periodEnd ?? row.period}`;
}

function dayCount(row: Summary): number | null {
  if (!row.periodStart || !row.periodEnd) {
    return null;
  }

  return (
    Math.round(
      (
        Date.parse(row.periodEnd) -
        Date.parse(row.periodStart)
      ) / 86400000,
    ) + 1
  );
}

function dailyEnergy(row: Summary): string {
  const days = dayCount(row);

  if (
    !row.actualEnergy ||
    days === null ||
    days <= 0
  ) {
    return "—";
  }

  return (
    Number(row.actualEnergy) / days
  ).toFixed(2);
}

function paymentEligibilityText(
  row: Summary,
): "APPROVED" | "PENDING" {
  return row.paymentEligibility === "ELIGIBLE"
    ? "APPROVED"
    : "PENDING";
}

function billingPointStatusText(
  row: Summary,
): string {
  return row.billingPointStatus === "DISABLED"
    ? "停用"
    : "启用";
}

function paymentEligibleFilter(): boolean | undefined {
  return filters.reviewStatus === "APPROVED"
    ? true
    : filters.reviewStatus === "PENDING"
      ? false
      : undefined;
}

function billingPointQuery() {
  return {
    code: filters.code.trim(),
    name: filters.name.trim(),
    cityCode: filters.cityCode,
    district: filters.district,
    period: filters.period,

    paymentEligible: paymentEligibleFilter(),
    billingPointStatus:
      filters.pointStatus,
    auditStatus: filters.auditStatus,
    reportStatus: filters.reportStatus,

    focusPeriod: filters.focusPeriod,
    focusCityCode:
      filters.focusCityCode,

    page: filters.page,
    size: filters.size,
  };
}

async function loadFilterOptions(): Promise<void> {
  const { district, page, size, ...query } = billingPointQuery();
  void district;
  void page;
  void size;
  filterOptions.value = await businessApi.billingPoints.filterOptions(query);
}

async function load(): Promise<void> {
  loading.value = true;
  errorMessage.value = "";

  try {
    const result =
      await businessApi.billingPoints.list(billingPointQuery());

    pageData.value = result;

    await nextTick();

    for (const row of result.items) {
      if (selectedIds.value.has(row.id)) {
        table.value?.toggleRowSelection(
          row,
          true,
        );
      }
    }
  } catch (error) {
    errorMessage.value =
      error instanceof Error
        ? error.message
        : "报账点列表加载失败";
  } finally {
    loading.value = false;
  }
}

async function search(): Promise<void> {
  filters.page = 1;

  await router.replace({
    path: "/billing-points",
    query: routeQuery(),
  });

  await loadFilterOptions();
  await load();
}

async function reset(): Promise<void> {
  Object.assign(filters, {
    code: "",
    name: "",
    cityCode:
      session.currentUser?.city?.code ?? "",
    district: "",
    period: "",
    reviewStatus: "",
    pointStatus: "",
    auditStatus: "",
    reportStatus: "",
    focusPeriod: "",
    focusCityCode: "",
    page: 1,
  });

  await search();
}

async function changePage(
  page: number,
): Promise<void> {
  filters.page = page;

  await router.replace({
    path: "/billing-points",
    query: routeQuery(),
  });

  await load();
}

async function changeSize(
  size: number,
): Promise<void> {
  filters.size = size;
  filters.page = 1;

  await router.replace({
    path: "/billing-points",
    query: routeQuery(),
  });

  await load();
}

function selectionChanged(
  rows: Summary[],
): void {
  const currentIds = new Set(
    pageData.value?.items.map(
      (item) => item.id,
    ) ?? [],
  );

  for (const id of currentIds) {
    selectedIds.value.delete(id);
  }

  for (const row of rows) {
    selectedIds.value.add(row.id);
  }

  selectedIds.value =
    new Set(selectedIds.value);
}

async function openDetail(
  row: Summary,
): Promise<void> {
  await router.push({
    name: "billing-point-detail",
    params: {
      billingPointCode: row.id,
      period: row.period,
    },
    query: {
      from: route.fullPath,
    },
  });
}

async function openDraft(
  row: Summary,
): Promise<void> {
  if (isAiAnalyzing(row)) {
    ElMessage.info("AI正在后台分析，完成或失败后可继续生成报告。");
    return;
  }
  const draft =
    await businessApi.drafts.createOrResume(
      row.id,
    );

  await router.push({
    name: "report-draft",
    params: {
      draftId: draft.id,
    },
    query: {
      from: route.fullPath,
    },
  });
}

async function openReport(
  row: Summary,
): Promise<void> {
  const reports =
    await businessApi.reports.list({
      cityCode: row.city.code,
      district: "",
      period: row.period,
      keyword: row.code,
      source: "",
      page: 1,
      size: 10,
    });

  const report = reports.items.find(
    (item) =>
      item.billingPointCode === row.code,
  );

  if (report === undefined) {
    ElMessage.error(
      "未找到该报账点的正式报告。",
    );
    return;
  }

  await router.push({
    name: "report-detail",
    params: {
      reportId: report.id,
    },
    query: {
      from: route.fullPath,
    },
  });
}

function closeImport(
  visible: boolean,
): void {
  importVisible.value = visible;

  if (
    !visible &&
    route.query.dialog === "import"
  ) {
    void router.replace({
      path: "/billing-points",
      query: routeQuery(),
    });
  }
}

async function handleImported(
  batches: ImportBatch[],
): Promise<void> {
  void batches;

  selectedIds.value =
    new Set<string>();

  Object.assign(filters, {
    code: "",
    name: "",
    cityCode:
      session.currentUser?.city?.code ?? "",
    district: "",
    period: "",
    reviewStatus: "",
    pointStatus: "",
    auditStatus: "",
    reportStatus: "",
    focusPeriod: "",
    focusCityCode: "",
    page: 1,
  });

  importVisible.value = false;

  await router.replace({
    path: "/billing-points",
    query: routeQuery(),
  });

  await loadFilterOptions();
  await load();
}

onMounted(async () => {
  hydrateFromRoute();

  cities.value =
    await businessApi.cities.list();

  await loadFilterOptions();
  await load();
});

watch(
  () => route.query.dialog,
  (value) => {
    if (value === "import") {
      importVisible.value = true;
    }
  },
);
</script>

<template>
  <section
    class="filter-card business-card"
    :class="{
      'filter-card-collapsed':
        filterCollapsed,
    }"
    aria-label="报账点查询"
  >
    <div class="filter-card-header">
      <strong class="filter-card-title">
        查询条件
      </strong>

      <ElButton
        class="filter-collapse-button"
        link
        type="primary"
        @click="
          filterCollapsed =
            !filterCollapsed
        "
      >
        {{
          filterCollapsed
            ? "展开"
            : "收起"
        }}

        <span class="collapse-arrow">
          {{
            filterCollapsed
              ? "⌄"
              : "⌃"
          }}
        </span>
      </ElButton>
    </div>

    <ElCollapseTransition>
      <div
        v-show="!filterCollapsed"
        class="filter-grid"
      >
        <label>
          <span>报账点编码</span>

          <ElInput
            v-model="filters.code"
            placeholder="请输入编码"
            clearable
          />
        </label>

        <label>
          <span>报账点名称</span>

          <ElInput
            v-model="filters.name"
            placeholder="请输入名称"
            clearable
          />
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

          <ElSelect
            v-model="filters.district"
            placeholder="全部区县"
            clearable
          >
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
            placeholder="全部账期"
            clearable
          />
        </label>

        <label>
          <span>审核状态</span>

          <ElSelect
            v-model="filters.reviewStatus"
            placeholder="全部"
            clearable
          >
            <ElOption
              label="审核通过"
              value="APPROVED"
            />
            <ElOption
              label="审核不通过"
              value="PENDING"
            />
          </ElSelect>
        </label>

        <label>
          <span>报账点状态</span>

          <ElSelect
            v-model="filters.pointStatus"
            placeholder="全部"
            clearable
          >
            <ElOption
              label="启用"
              value="ENABLED"
            />
            <ElOption
              label="停用"
              value="DISABLED"
            />
          </ElSelect>
        </label>

        <label>
          <span>稽核状态</span>

          <ElSelect
            v-model="filters.auditStatus"
            placeholder="全部"
            clearable
          >
            <ElOption
              label="正常"
              value="NORMAL"
            />
            <ElOption
              label="超标"
              value="OVER_LIMIT"
            />
            <ElOption
              label="数据不足"
              value="NOT_APPLICABLE"
            />
          </ElSelect>
        </label>

        <label>
          <span>报告状态</span>

          <ElSelect
            v-model="filters.reportStatus"
            placeholder="全部"
            clearable
          >
            <ElOption
              label="待生成"
              value="DRAFT"
            />
            <ElOption
              label="已生成"
              value="FINAL"
            />
            <ElOption
              label="无需生成"
              value="NONE"
            />
          </ElSelect>
        </label>

        <div class="filter-actions">
          <ElButton
            type="primary"
            :icon="Search"
            :loading="loading"
            @click="search"
          >
            查询
          </ElButton>

          <ElButton
            :icon="Refresh"
            @click="reset"
          >
            重置
          </ElButton>
        </div>
      </div>
    </ElCollapseTransition>
  </section>

  <div class="business-toolbar">
    <div>
      <ElButton
        type="primary"
        :icon="Upload"
        @click="importVisible = true"
      >
        导入数据
      </ElButton>

      <ElButton
        :icon="Download"
        :disabled="
          selectedCount === 0
        "
        @click="exportVisible = true"
      >
        导出Excel
      </ElButton>
    </div>

    <span>
      <template
        v-if="selectedCount > 0"
      >
        已选择
        <b class="number-emphasis">
          {{ selectedCount }}
        </b>
        条，
      </template>

      共
      <b class="number-emphasis">
        {{
          pageData?.totalElements ??
          0
        }}
      </b>
      个报账点
    </span>
  </div>

  <PageState
    v-if="!pageData && loading"
    kind="loading"
  />

  <PageState
    v-else-if="errorMessage"
    kind="error"
    title="报账点列表加载失败"
    :description="errorMessage"
    @retry="load"
  />

  <section
    v-else
    class="table-card business-card"
  >
    <ElTable
      ref="table"
      v-loading="loading"
      :data="pageData?.items ?? []"
      row-key="id"
      @selection-change="
        selectionChanged
      "
    >
      <ElTableColumn
        type="selection"
        width="48"
      />

      <ElTableColumn
        prop="code"
        label="报账点编码"
        width="205"
      />

      <ElTableColumn
        prop="name"
        label="报账点名称"
        min-width="180"
      />

      <ElTableColumn
        prop="city.name"
        label="所属地市"
        width="110"
      />

      <ElTableColumn
        label="所属区县"
        width="110"
      >
        <template #default="scope">
          {{
            asSummary(scope.row)
              .district ?? "—"
          }}
        </template>
      </ElTableColumn>

      <ElTableColumn
        label="账期"
        width="210"
      >
        <template #default="scope">
          {{
            periodText(
              asSummary(scope.row),
            )
          }}
        </template>
      </ElTableColumn>

      <ElTableColumn
        label="缴费天数"
        width="95"
        align="right"
      >
        <template #default="scope">
          {{
            dayCount(
              asSummary(scope.row),
            ) ?? "—"
          }}
        </template>
      </ElTableColumn>

      <ElTableColumn
        label="日均耗电量"
        width="120"
        align="right"
      >
        <template #default="scope">
          {{
            dailyEnergy(
              asSummary(scope.row),
            )
          }}
        </template>
      </ElTableColumn>

      <ElTableColumn
        prop="actualEnergy"
        label="实际总耗电量"
        width="130"
        align="right"
      />

      <ElTableColumn
        label="实际报账金额"
        width="135"
        align="right"
      >
        <template #default="scope">
          ¥{{
            asSummary(scope.row)
              .actualAmount ?? "—"
          }}
        </template>
      </ElTableColumn>

      <ElTableColumn
        label="审核状态"
        width="115"
      >
        <template #default="scope">
          <StatusTag
            :value="
              paymentEligibilityText(
                asSummary(scope.row),
              )
            "
          />
        </template>
      </ElTableColumn>

      <ElTableColumn
        label="报账点状态"
        width="115"
      >
        <template #default="scope">
          <ElTag
            :type="
              billingPointStatusText(
                asSummary(scope.row),
              ) === '启用'
                ? 'success'
                : 'info'
            "
            size="small"
          >
            {{
              billingPointStatusText(
                asSummary(scope.row),
              )
            }}
          </ElTag>
        </template>
      </ElTableColumn>

      <ElTableColumn
        label="稽核状态"
        width="115"
      >
        <template #default="scope">
          <StatusTag
            :value="
              asSummary(scope.row)
                .auditStatus
            "
          />
        </template>
      </ElTableColumn>

      <ElTableColumn
        label="超标比例"
        min-width="310"
      >
        <template #default="scope">
          <OverLimitRatioTags
            v-if="asSummary(scope.row).auditStatus === 'OVER_LIMIT'"
            :ratios="asSummary(scope.row).overLimitRatios"
            quiet
          />
          <span v-else>—</span>
        </template>
      </ElTableColumn>

      <ElTableColumn
        label="报告状态"
        width="115"
      >
        <template #default="scope">
          <StatusTag
            :value="
              asSummary(scope.row)
                .reportStatus
            "
          />
        </template>
      </ElTableColumn>

      <ElTableColumn
        label="操作"
        width="128"
        fixed="right"
      >
        <template #default="scope">
          <div
            class="text-link-actions row-command-actions"
          >
            <ElButton
              link
              type="primary"
              @click="
                openDetail(
                  asSummary(scope.row),
                )
              "
            >
              查看
            </ElButton>

            <ElButton
              v-if="
                asSummary(scope.row)
                  .reportStatus ===
                'DRAFT'
              "
              link
              type="danger"
              :disabled="
                isAiAnalyzing(
                  asSummary(scope.row),
                )
              "
              @click="
                openDraft(
                  asSummary(scope.row),
                )
              "
            >
              {{
                isAiAnalyzing(
                  asSummary(scope.row),
                )
                  ? "AI分析中"
                  : "生成报告"
              }}
            </ElButton>

            <ElButton
              v-if="
                asSummary(scope.row)
                  .reportStatus ===
                  'FINAL' ||
                asSummary(scope.row)
                  .reportStatus ===
                  'CORRECTED'
              "
              link
              type="success"
              @click="
                openReport(
                  asSummary(scope.row),
                )
              "
            >
              查看报告
            </ElButton>
          </div>
        </template>
      </ElTableColumn>

      <template #empty>
        <ElEmpty
          description="当前条件下没有报账点数据"
        />
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
        :total="
          pageData?.totalElements ??
          0
        "
        @current-change="changePage"
        @size-change="changeSize"
      />
    </footer>
  </section>

  <ImportDataDialog
    :model-value="importVisible"
    :default-period="filters.period"
    @update:model-value="closeImport"
    @imported="handleImported"
  />

  <ExportDataDialog
    v-model="exportVisible"
    :scope-label="`当前已选择 ${selectedCount} 条记录`"
    period=""
    city-code=""
    :billing-point-ids="
      selectedIdList
    "
    :selected-count="selectedCount"
  />
</template>

<style scoped>
.filter-card {
  padding: 12px 16px 14px;

  transition:
    padding 0.2s ease,
    min-height 0.2s ease;
}

.filter-card-header {
  display: flex;

  min-height: 28px;

  align-items: center;
  justify-content: space-between;
}

.filter-card:not(
    .filter-card-collapsed
  )
  .filter-card-header {
  margin-bottom: 12px;
}

.filter-card-title {
  color: #1f2d3d;
  font-size: 14px;
  font-weight: 700;
}

.filter-collapse-button {
  height: 28px;
  padding: 0 2px;

  font-size: 13px;
}

.collapse-arrow {
  display: inline-block;

  margin-left: 5px;

  font-size: 15px;
  line-height: 1;
}

.filter-card-collapsed {
  padding-top: 10px;
  padding-bottom: 10px;
}

.multi-value-cell {
  display: inline-block;

  white-space: normal;
  overflow-wrap: anywhere;

  line-height: 1.45;
}

.filter-grid {
  display: grid;

  grid-template-columns:
    repeat(
      auto-fit,
      minmax(
        min(100%, 150px),
        1fr
      )
    );

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

  grid-column: -2 / -1;

  gap: 10px;
  align-items: end;
  justify-content: flex-end;
  justify-self: end;
  align-self: end;

  padding-top: 4px;

  white-space: nowrap;
}

.filter-actions .el-button {
  min-width: 78px;
}

/*
 * 列表保留横向滚动，
 * 不再设置固定高度和内部纵向滚动。
 * 当前页仍由分页控制，只展示当前页数据。
 */
.table-card {
  overflow-x: auto;
  overflow-y: visible;
}

.table-card :deep(.el-table),
.table-card :deep(.el-table__inner-wrapper),
.table-card :deep(.el-table__body-wrapper) {
  height: auto !important;
  max-height: none !important;
}

.table-card
  :deep(
    .el-table__body-wrapper
  ) {
  overflow-y: visible !important;
}

.table-card
  :deep(
    .el-table__empty-block
  ) {
  min-height: 220px;
}

.table-card
  :deep(
    td.el-table__fixed-column--right
      .cell
  ) {
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
  font-size: 14px;

  border-top: 1px solid #edf1f5;
}

@media (width <= 1280px) {
  .filter-grid {
    grid-template-columns:
      repeat(
        4,
        minmax(150px, 1fr)
      );
  }
}

@media (width <= 720px) {
  .filter-grid {
    grid-template-columns: 1fr;
  }

  .business-toolbar,
  .table-footer {
    width: 100%;

    align-items: flex-start;
    flex-direction: column;
  }

  .filter-actions,
  .filter-actions .el-button {
    width: 100%;
  }
}
</style>
