<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { Refresh, Search, View } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";

import { businessApi } from "@/api/business-api";
import PageState from "@/components/PageState.vue";
import type { AiTaskListItem, AiTaskPage, AiTaskStatus } from "@/types/business";

const router = useRouter();
const loading = ref(false);
const errorMessage = ref("");
const pageData = ref<AiTaskPage | null>(null);
const retryingId = ref("");
let refreshTimer: ReturnType<typeof window.setTimeout> | null = null;

const filters = reactive({
  status: "" as AiTaskStatus | "",
  billingPointName: "",
  cityName: "",
  period: "",
  page: 1,
  size: 10,
});

const cityOptions = computed(() => pageData.value?.filterOptions?.cityNames ?? []);

const statusOptions: Array<{ label: string; value: AiTaskStatus }> = [
  { label: "排队中", value: "QUEUED" },
  { label: "AI分析中", value: "RUNNING" },
  { label: "等待重试", value: "RETRY_WAIT" },
  { label: "AI完成待确认", value: "SUCCEEDED" },
  { label: "AI分析失败", value: "FAILED" },
];

const rangeLabel = computed(() => {
  const total = pageData.value?.totalElements ?? 0;
  if (total === 0) return "已显示 0-0 条，共 0 条";
  const start = (filters.page - 1) * filters.size + 1;
  const end = Math.min(filters.page * filters.size, total);
  return `已显示 ${start}-${end} 条，共 ${total} 条`;
});

async function load(silent = false): Promise<void> {
  if (!silent) loading.value = true;
  errorMessage.value = "";
  try {
    pageData.value = await businessApi.aiTasks.list(filters);
  } catch (error) {
    errorMessage.value =
      error instanceof Error ? error.message : "AI任务进度加载失败";
  } finally {
    if (!silent) loading.value = false;
    scheduleRefresh();
  }
}

function scheduleRefresh(): void {
  stopRefresh();
  refreshTimer = window.setTimeout(() => {
    void load(true);
  }, 5000);
}

function stopRefresh(): void {
  if (refreshTimer !== null) {
    window.clearTimeout(refreshTimer);
    refreshTimer = null;
  }
}

function search(): void {
  filters.page = 1;
  void load();
}

function reset(): void {
  filters.status = "";
  filters.billingPointName = "";
  filters.cityName = "";
  filters.period = "";
  filters.page = 1;
  void load();
}

function changePage(page: number): void {
  filters.page = page;
  void load();
}

function changeSize(size: number): void {
  filters.size = size;
  filters.page = 1;
  void load();
}

function taskStatusLabel(task: AiTaskListItem): string {
  if (task.status === "SUCCEEDED") return "AI完成待确认";
  const labels: Record<AiTaskStatus, string> = {
    QUEUED: "排队中",
    RUNNING: "AI分析中",
    RETRY_WAIT: "等待重试",
    SUCCEEDED: "AI完成待确认",
    FAILED: "AI分析失败",
  };
  return labels[task.status];
}

function taskStatusType(task: AiTaskListItem): "info" | "warning" | "success" | "danger" {
  if (task.status === "RUNNING" || task.status === "RETRY_WAIT") return "warning";
  if (task.status === "SUCCEEDED") return "success";
  if (task.status === "FAILED") return "danger";
  return "info";
}

function errorText(task: AiTaskListItem): string {
  if (task.status !== "FAILED") return "—";
  const messages: Record<string, string> = {
    AI_IMAGE_ANALYSIS_FAILED:
      "AI图片分析失败，请检查密钥配置或稍后重新分析。",
    KIMI_AUTH_FAILED: "Kimi 密钥无效或无权限，请检查 KIMI_API_KEY。",
    KIMI_MODEL_UNAVAILABLE: "Kimi 模型不可用，请检查 KIMI_MODEL 配置。",
    KIMI_TIMEOUT: "Kimi 调用超时，请稍后重新分析。",
    KIMI_IMAGE_INVALID: "图片过大或格式不支持，请减少图片数量或重新粘贴。",
    AI_RESPONSE_INVALID: "Kimi 返回内容格式不符合要求，请重新分析。",
    TASK_PAYLOAD_INVALID: "AI图片分析任务数据异常，请重新提交。",
    AI_IMAGES_REQUIRED: "请先在报告正文中粘贴至少一张图片。",
  };
  if (!task.errorCode) return "AI图片分析失败，请稍后重试。";
  return messages[task.errorCode] ?? "AI图片分析失败，请稍后重试。";
}

function formatTaskTime(value: string): string {
  return value.replace("T", " ").slice(0, 19);
}

async function openDraft(task: AiTaskListItem): Promise<void> {
  if (!task.relatedDraftId) return;
  await router.push({
    name: "report-draft",
    params: { draftId: task.relatedDraftId },
    query: { from: "/reports/ai-tasks" },
  });
}

async function retryTask(task: AiTaskListItem): Promise<void> {
  retryingId.value = task.id;
  try {
    await businessApi.aiTasks.retry(task.id);
    ElMessage.success("AI任务已重新提交。");
    await load(true);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "AI任务重试失败");
  } finally {
    retryingId.value = "";
  }
}

onMounted(() => {
  void load();
});

onBeforeUnmount(stopRefresh);
</script>

<template>
  <section class="ai-task-filter business-card">
    <div class="ai-task-filter-row">
      <label>
        <span>任务状态</span>
        <ElSelect v-model="filters.status" placeholder="全部状态" clearable>
          <ElOption
            v-for="option in statusOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </ElSelect>
      </label>

      <label>
        <span>报账点名称</span>
        <ElInput
          v-model="filters.billingPointName"
          placeholder="请输入报账点名称"
          clearable
          @keyup.enter="search"
        />
      </label>

      <label>
        <span>城市</span>
        <ElSelect
          v-model="filters.cityName"
          placeholder="全部城市"
          clearable
        >
          <ElOption
            v-for="city in cityOptions"
            :key="city"
            :label="city"
            :value="city"
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

      <div class="ai-task-query-actions">
        <ElButton
          class="ai-task-query-button"
          type="primary"
          :icon="Search"
          :loading="loading"
          @click="search"
        >
          查询
        </ElButton>
        <ElButton class="ai-task-query-button" :icon="Refresh" @click="reset">
          重置
        </ElButton>
      </div>
    </div>
  </section>

  <PageState
    v-if="!pageData && loading"
    kind="loading"
  />
  <PageState
    v-else-if="errorMessage"
    kind="error"
    title="AI任务进度加载失败"
    :description="errorMessage"
    @retry="load"
  />

  <section v-else class="business-card task-table-card">
    <div class="task-table-scroll">
    <ElTable
      v-loading="loading"
      class="ai-task-table"
      :data="pageData?.items ?? []"
      table-layout="auto"
    >
      <ElTableColumn label="报账点编码" min-width="190" show-overflow-tooltip>
        <template #default="scope">
          {{ (scope.row as AiTaskListItem).billingPointCode ?? "—" }}
        </template>
      </ElTableColumn>

      <ElTableColumn label="报账点名称" min-width="180" show-overflow-tooltip>
        <template #default="scope">
          {{ (scope.row as AiTaskListItem).billingPointName ?? "—" }}
        </template>
      </ElTableColumn>

      <ElTableColumn prop="cityName" label="所属地市" min-width="130" show-overflow-tooltip />
      <ElTableColumn prop="period" label="账期" min-width="120" />

      <ElTableColumn label="AI任务状态" min-width="150">
        <template #default="scope">
          <ElTag
            :type="taskStatusType(scope.row as AiTaskListItem)"
            size="small"
          >
            {{ taskStatusLabel(scope.row as AiTaskListItem) }}
          </ElTag>
        </template>
      </ElTableColumn>

      <ElTableColumn label="错误原因" min-width="260" show-overflow-tooltip>
        <template #default="scope">
          {{ errorText(scope.row as AiTaskListItem) }}
        </template>
      </ElTableColumn>

      <ElTableColumn label="任务提交时间" min-width="180">
        <template #default="scope">
          {{ formatTaskTime((scope.row as AiTaskListItem).createdAt) }}
        </template>
      </ElTableColumn>

      <ElTableColumn label="操作" width="190" fixed="right">
        <template #default="scope">
          <div class="row-actions">
            <ElButton
              v-if="(scope.row as AiTaskListItem).relatedDraftId"
              link
              type="primary"
              :icon="View"
              @click="openDraft(scope.row as AiTaskListItem)"
            >
              {{
                (scope.row as AiTaskListItem).status === "SUCCEEDED"
                  ? "查看并确认"
                  : "查看草稿"
              }}
            </ElButton>
            <span v-else class="muted">暂无可跳转</span>
            <ElButton
              v-if="(scope.row as AiTaskListItem).canRetry"
              link
              type="danger"
              :loading="retryingId === (scope.row as AiTaskListItem).id"
              @click="retryTask(scope.row as AiTaskListItem)"
            >
              重试
            </ElButton>
          </div>
        </template>
      </ElTableColumn>

      <template #empty>
        <ElEmpty description="暂无AI图片分析任务" />
      </template>
    </ElTable>
    </div>

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
</template>

<style scoped>
.ai-task-filter {
  padding: 14px 16px;
  margin-bottom: 14px;
  overflow: visible;
}

.ai-task-filter-row {
  display: grid;
  width: 100%;
  min-width: 0;
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

.ai-task-filter-row label {
  display: grid;
  min-width: 0;
  gap: 6px;
  color: #1f2d3d;
  font-size: 14px;
  font-weight: 600;
}

.ai-task-filter-row span {
  color: inherit;
  font: inherit;
}

.ai-task-filter-row :deep(.el-select),
.ai-task-filter-row :deep(.el-date-editor),
.ai-task-filter-row :deep(.el-input) {
  width: 100%;
  min-width: 0;
}

.ai-task-query-actions {
  display: flex;
  grid-column: -2 / -1;
  gap: 10px;
  align-items: end;
  justify-content: flex-end;
  justify-self: end;
  align-self: end;
  margin-left: auto;
  padding-top: 4px;
  white-space: nowrap;
}

.ai-task-query-actions :deep(.el-button) {
  flex: 0 0 auto;
  min-width: 78px;
  margin: 0 !important;
}

.task-table-card {
  overflow: hidden;
}

.task-table-scroll {
  overflow-x: auto;
}

.ai-task-table {
  width: 100%;
  min-width: 0;
}

.row-actions {
  display: flex;
  flex-wrap: nowrap;
  gap: 8px;
  align-items: center;
  white-space: nowrap;
}

.muted {
  color: var(--color-neutral-400);
  font-size: 13px;
}

.table-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  color: var(--color-neutral-500);
  border-top: 1px solid var(--color-neutral-200);
}

@media (width <= 960px) {
  .ai-task-filter {
    padding: 12px;
  }

  .ai-task-filter-row {
    gap: 8px;
  }

  .ai-task-filter-row :deep(.el-input__wrapper),
  .ai-task-filter-row :deep(.el-select__wrapper) {
    padding-right: 6px;
    padding-left: 6px;
  }

  .ai-task-query-actions {
    gap: 6px;
  }

  .ai-task-query-actions :deep(.el-button),
  .ai-task-query-button {
    min-width: 58px;
    padding: 0 6px !important;
  }
}
</style>
