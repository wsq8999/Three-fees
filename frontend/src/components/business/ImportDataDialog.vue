<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { UploadFile } from "element-plus";
import { ElMessageBox } from "element-plus";

import { businessApi } from "@/api/business-api";
import { ApiProblem } from "@/api/problem-details";
import type { DatasetType, ImportBatch } from "@/types/business";

const props = defineProps<{ modelValue: boolean; defaultPeriod?: string }>();
const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  imported: [batch: ImportBatch];
}>();

const datasetTypes: DatasetType[] = [
  "BILLING_POINT",
  "PAYMENT",
  "METER_READING",
  "BENCHMARK",
];

const datasetMeta: Record<
  DatasetType,
  { label: string; description: string; fieldCount: number }
> = {
  BILLING_POINT: {
    label: "报账点清单",
    description: "基础报账点和归属关系",
    fieldCount: 73,
  },
  PAYMENT: {
    label: "缴费明细",
    description: "缴费单及审核、金额信息",
    fieldCount: 198,
  },
  METER_READING: {
    label: "电表读数",
    description: "电表抄表、分摊读数数据",
    fieldCount: 42,
  },
  BENCHMARK: {
    label: "标杆数据",
    description: "额定功率标杆数据",
    fieldCount: 39,
  },
};

const period = ref(props.defaultPeriod ?? "2026-06");
const selectedType = ref<DatasetType>("BILLING_POINT");
const selectedFile = ref<File | null>(null);
const batches = ref<ImportBatch[]>([]);
const runningBatch = ref<ImportBatch | null>(null);
const runningBatches = ref<ImportBatch[]>([]);
const isSubmitting = ref(false);
const errorMessage = ref("");

const activeByType = computed(
  () =>
    new Map(
      batches.value
        .filter(
          (batch) => batch.period === period.value && batch.status === "ACTIVE",
        )
        .map((batch) => [batch.datasetType, batch]),
    ),
);

const canSubmit = computed(
  () =>
    selectedFile.value !== null &&
    !isSubmitting.value &&
    runningBatch.value === null,
);

async function loadBatches(): Promise<void> {
  batches.value = await businessApi.imports.list();
}

function resetForm(): void {
  selectedType.value = "BILLING_POINT";
  selectedFile.value = null;
  runningBatch.value = null;
  runningBatches.value = [];
  errorMessage.value = "";
}

function handleFileChange(file: UploadFile): void {
  errorMessage.value = "";
  const raw = file.raw;
  if (raw === undefined) return;
  if (!/\.(?:xlsx|xls|csv)$/i.test(raw.name)) {
    errorMessage.value = "只支持 .xlsx、.xls 或 .csv 文件。";
    selectedFile.value = null;
    return;
  }
  if (raw.size === 0) {
    errorMessage.value = "不能导入空文件，请重新选择。";
    selectedFile.value = null;
    return;
  }
  selectedFile.value = raw;
}

async function pollBatches(ids: string[]): Promise<void> {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    const latestList = (
      await Promise.all(ids.map((id) => businessApi.imports.get(id)))
    ).filter((item): item is ImportBatch => item !== undefined);
    runningBatches.value = latestList;
    runningBatch.value = latestList[0] ?? null;
    if (
      latestList.length === ids.length &&
      latestList.every(
        (batch) => batch.status === "ACTIVE" || batch.status === "FAILED",
      )
    ) {
      await loadBatches();
      latestList
        .filter((batch) => batch.status === "ACTIVE")
        .forEach((batch) => emit("imported", batch));
      return;
    }
    await new Promise((resolve) => globalThis.setTimeout(resolve, 500));
  }
  errorMessage.value = "导入任务仍在后台处理中，可关闭窗口后稍后刷新查看。";
}

async function submit(): Promise<void> {
  if (selectedFile.value === null) return;
  errorMessage.value = "";
  const current = activeByType.value.get(selectedType.value);
  if (current !== undefined) {
    try {
      await ElMessageBox.confirm(
        `当前已存在激活的${datasetMeta[selectedType.value].label}批次。新批次校验成功后将整批替换。`,
        "确认整批替换",
        {
          confirmButtonText: "继续导入",
          cancelButtonText: "取消",
          type: "warning",
        },
      );
    } catch {
      return;
    }
  }
  isSubmitting.value = true;
  try {
    const created = await businessApi.imports.create(
      {
        datasetType: selectedType.value,
        fileName: selectedFile.value.name,
      },
      selectedFile.value,
    );
    runningBatches.value = created;
    runningBatch.value = created[0] ?? null;
    await pollBatches(created.map((batch) => batch.id));
  } catch (error) {
    errorMessage.value =
      error instanceof ApiProblem && error.fieldErrors.length > 0
        ? `${error.message}：${error.fieldErrors
            .slice(0, 5)
            .map((item) => `${item.field} ${item.code} ${item.message}`)
            .join("；")}`
        : error instanceof Error
          ? error.message
          : "导入任务提交失败";
  } finally {
    isSubmitting.value = false;
  }
}

async function retry(): Promise<void> {
  const failed = runningBatches.value.filter((batch) => batch.status === "FAILED");
  if (failed.length === 0 && runningBatch.value === null) return;
  isSubmitting.value = true;
  try {
    const retried = await Promise.all(
      (failed.length > 0 ? failed : [runningBatch.value as ImportBatch]).map(
        (batch) => businessApi.imports.retry(batch.id),
      ),
    );
    runningBatches.value = retried;
    runningBatch.value = retried[0] ?? null;
    await pollBatches(retried.map((batch) => batch.id));
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "重试失败";
  } finally {
    isSubmitting.value = false;
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return;
    period.value = props.defaultPeriod ?? "2026-06";
    resetForm();
    void loadBatches();
  },
);
</script>

<template>
  <ElDialog
    :model-value="modelValue"
    title="导入数据"
    width="min(1040px, 94vw)"
    top="6vh"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="import-form">
      <div class="form-row type-row">
        <strong><span>*</span> 导入类型</strong>
        <div class="dataset-grid">
          <button
            v-for="(datasetType, index) in datasetTypes"
            :key="datasetType"
            type="button"
            class="dataset-card"
            :class="{ selected: selectedType === datasetType }"
            :disabled="runningBatch !== null"
            @click="selectedType = datasetType"
          >
            <b>{{ String(index + 1).padStart(2, "0") }}</b>
            <span>
              <strong>{{ datasetMeta[datasetType].label }}</strong>
              <small>
                {{ datasetMeta[datasetType].description }} ·
                {{ datasetMeta[datasetType].fieldCount }} 列
              </small>
            </span>
            <i v-if="selectedType === datasetType" aria-hidden="true" />
          </button>
        </div>
      </div>

      <div class="form-row upload-row">
        <strong><span>*</span> 上传文件</strong>
        <ElUpload
          class="file-upload"
          drag
          :auto-upload="false"
          :limit="1"
          accept=".xlsx,.xls,.csv"
          :disabled="runningBatch !== null"
          :on-change="handleFileChange"
          :on-remove="() => (selectedFile = null)"
        >
          <div v-if="selectedFile" class="selected-file">
            <b>{{ selectedFile.name.split(".").pop()?.toUpperCase() }}</b>
            <span>
              <strong>{{ selectedFile.name }}</strong>
              <small>
                {{ (selectedFile.size / 1024).toFixed(1) }} KB · 文件类型与表头将在服务端复核
              </small>
            </span>
          </div>
          <template v-else>
            <strong>点击或将文件拖到此处</strong>
            <small>支持 .xlsx、.xls、.csv；一次只选择一个文件</small>
          </template>
        </ElUpload>
      </div>

      <ElAlert
        title="系统将根据文件中的所属地市/地市自动识别城市，并根据文件日期自动拆分账期；电表读数将通过报账点或缴费单关联识别。"
        type="warning"
        :closable="false"
        show-icon
      />

      <section v-if="runningBatches.length > 0 || runningBatch" class="task-panel">
        <div>
          <strong>导入任务</strong>
          <span>{{ selectedFile?.name }}</span>
        </div>
        <div
          v-for="batch in runningBatches.length > 0 ? runningBatches : runningBatch ? [runningBatch] : []"
          :key="batch.id"
          class="batch-result"
        >
          <div>
            <strong>{{ batch.period }} / {{ batch.cityCode ?? "-" }}</strong>
            <span>{{ batch.status }}</span>
          </div>
          <ElProgress
            :percentage="
              batch.status === 'ACTIVE' || batch.status === 'FAILED'
                ? 100
                : batch.status === 'PROCESSING'
                  ? 66
                  : 20
            "
            :status="
              batch.status === 'FAILED'
                ? 'exception'
                : batch.status === 'ACTIVE'
                  ? 'success'
                  : undefined
            "
          />
          <ElAlert
            v-if="batch.status === 'FAILED'"
            class="import-errors"
            type="error"
            :closable="false"
            show-icon
          >
            <ul>
              <li
                v-for="item in batch.errors"
                :key="`${batch.id}-${item.row}-${item.column}-${item.code ?? ''}`"
              >
                第 {{ item.row }} 行 {{ item.column }}
                <template v-if="item.code">（{{ item.code }}）</template>：{{ item.message }}
              </li>
            </ul>
          </ElAlert>
        </div>
      </section>

      <ElAlert
        v-if="errorMessage"
        :title="errorMessage"
        type="error"
        :closable="false"
        show-icon
      />
    </div>

    <template #footer>
      <ElButton
        v-if="runningBatches.some((batch) => batch.status === 'FAILED') || runningBatch?.status === 'FAILED'"
        :loading="isSubmitting"
        @click="retry"
      >
        重新提交任务
      </ElButton>
      <ElButton
        @click="
          emit('update:modelValue', false);
          resetForm();
        "
      >
        {{
          runningBatch && !['ACTIVE', 'FAILED'].includes(runningBatch.status)
            ? '关闭观察窗口'
            : '取消'
        }}
      </ElButton>
      <ElButton
        type="primary"
        :loading="isSubmitting"
        :disabled="!canSubmit"
        @click="submit"
      >
        确认导入
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.import-form {
  display: grid;
  gap: 18px;
}

.form-row {
  display: grid;
  grid-template-columns: 112px minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.form-row > strong {
  padding-top: 11px;
  color: #1f2d3d;
  font-size: 14px;
}

.form-row > strong span {
  color: #ff3152;
}

.dataset-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.dataset-card {
  position: relative;
  display: flex;
  min-height: 116px;
  gap: 14px;
  align-items: center;
  padding: 26px 28px;
  color: #1f2d3d;
  text-align: left;
  background: #fff;
  border: 1px solid #d7e0ee;
  border-radius: 12px;
  cursor: pointer;
  transition:
    border-color 0.16s ease,
    background 0.16s ease;
}

.dataset-card:hover,
.dataset-card.selected {
  background: #fff5f7;
  border-color: #ff3152;
}

.dataset-card > b {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  color: #ff3152;
  background: #fff0f3;
  border-radius: 8px;
}

.dataset-card > span {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
}

.dataset-card small {
  color: #52657a;
}

.dataset-card i {
  position: absolute;
  top: 18px;
  right: 18px;
  width: 12px;
  height: 12px;
  background: #ff3152;
  border-radius: 50%;
}

.file-upload :deep(.el-upload),
.file-upload :deep(.el-upload-dragger) {
  width: 100%;
}

.file-upload :deep(.el-upload-dragger) {
  display: flex;
  min-height: 132px;
  flex-direction: column;
  gap: 10px;
  justify-content: center;
  background: #fff;
  border: 1px dashed #d7e0ee;
  border-radius: 10px;
}

.file-upload :deep(.el-upload-dragger:hover) {
  border-color: #ff3152;
}

.selected-file {
  display: flex;
  gap: 16px;
  align-items: center;
  text-align: left;
}

.selected-file > b {
  display: grid;
  width: 54px;
  height: 54px;
  place-items: center;
  color: #ff3152;
  border: 1px solid #ff3152;
  border-radius: 8px;
}

.selected-file span {
  display: flex;
  flex-direction: column;
}

.task-panel {
  display: grid;
  gap: 12px;
  padding: 16px;
  background: #f7f9fc;
  border: 1px solid #e5ecf6;
  border-radius: 10px;
}

.task-panel > div:first-child,
.batch-result > div:first-child {
  display: flex;
  justify-content: space-between;
}

.batch-result {
  display: grid;
  gap: 8px;
  padding: 12px;
  background: #fff;
  border: 1px solid #e5ecf6;
  border-radius: 8px;
}

.task-panel span,
.task-panel p {
  color: #52657a;
  font-size: 13px;
}

.import-errors ul {
  max-height: 120px;
  overflow: auto;
}

@media (width <= 720px) {
  .form-row {
    grid-template-columns: 1fr;
  }

  .form-row > strong {
    padding-top: 0;
  }

  .dataset-grid {
    grid-template-columns: 1fr;
  }
}
</style>
