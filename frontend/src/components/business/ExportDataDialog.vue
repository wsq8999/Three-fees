<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";

import { businessApi, saveBlob } from "@/api/business-api";
import { DATASET_META, DATASET_TYPES } from "@/constants/datasets";
import type { DatasetType, ExportJob } from "@/types/business";

const props = defineProps<{
  modelValue: boolean;
  scopeLabel: string;
  selectedCount: number;
  period: string;
  cityCode: string;
  billingPointIds: string[];
}>();

const emit = defineEmits<{
  "update:modelValue": [value: boolean];
  exported: [];
}>();

const selectedTypes = ref<DatasetType[]>([]);
const status = ref<"idle" | "processing" | "success" | "failed">("idle");
const progress = ref(0);
const errorMessage = ref("");
const currentJob = ref<ExportJob | null>(null);

const canExport = computed(
  () =>
    props.selectedCount > 0 &&
    props.period.length > 0 &&
    selectedTypes.value.length > 0 &&
    status.value !== "processing",
);

function toggle(datasetType: DatasetType): void {
  if (status.value === "processing") return;
  const index = selectedTypes.value.indexOf(datasetType);
  if (index >= 0) selectedTypes.value.splice(index, 1);
  else selectedTypes.value.push(datasetType);
}

async function pollJob(jobId: string): Promise<ExportJob> {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const job = await businessApi.exportJobs.get(jobId);
    currentJob.value = job;
    if (job.status === "SUCCEEDED") return job;
    if (job.status === "FAILED") {
      throw new Error(job.errorCode ?? "导出任务失败");
    }
    progress.value = Math.min(90, 30 + attempt * 2);
    await new Promise((resolve) => globalThis.setTimeout(resolve, 500));
  }
  throw new Error("导出任务仍在处理中，请稍后重试。");
}

async function startExport(): Promise<void> {
  if (!canExport.value) return;
  status.value = "processing";
  errorMessage.value = "";
  progress.value = 20;
  try {
    const job = await businessApi.exportJobs.create({
      period: props.period,
      cityCode: props.cityCode,
      datasetTypes: selectedTypes.value,
      billingPointIds: props.billingPointIds,
    });
    currentJob.value = job;
    const completed = await pollJob(job.id);
    progress.value = 100;
    saveBlob(
      await businessApi.exportJobs.download(completed),
      `${props.period}-报账点数据导出.zip`,
    );
    status.value = "success";
    emit("exported");
    ElMessage.success("导出文件已生成并开始下载。");
  } catch (error) {
    status.value = "failed";
    errorMessage.value = error instanceof Error ? error.message : "导出失败";
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return;
    selectedTypes.value = ["BILLING_POINT"];
    status.value = "idle";
    progress.value = 0;
    errorMessage.value = "";
    currentJob.value = null;
  },
);
</script>

<template>
  <ElDialog
    :model-value="modelValue"
    title="导出报账点数据"
    width="min(640px, 92vw)"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <h4>1. 选择导出数据</h4>
    <div class="export-grid">
      <button
        v-for="datasetType in DATASET_TYPES"
        :key="datasetType"
        type="button"
        class="export-card"
        :class="{ selected: selectedTypes.includes(datasetType) }"
        @click="toggle(datasetType)"
      >
        <strong>{{ DATASET_META[datasetType].label }}</strong>
        <small>{{ DATASET_META[datasetType].fieldCount }}列</small>
        <span>{{ selectedTypes.includes(datasetType) ? "✓" : "+" }}</span>
      </button>
    </div>

    <ElAlert
      class="scope-alert"
      :title="`导出范围：${scopeLabel}（${selectedCount} 个报账点/账期）`"
      type="info"
      :closable="false"
      show-icon
    />

    <section v-if="status !== 'idle'" class="export-progress">
      <ElProgress
        :percentage="progress"
        :status="
          status === 'failed'
            ? 'exception'
            : status === 'success'
              ? 'success'
              : undefined
        "
      />
      <p v-if="status === 'success'">任务完成，文件已开始下载。</p>
      <p v-else-if="status === 'failed'">{{ errorMessage }}</p>
      <p v-else>导出任务处理中：{{ currentJob?.id ?? "提交中" }}</p>
    </section>

    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">取消</ElButton>
      <ElButton
        type="primary"
        :loading="status === 'processing'"
        :disabled="!canExport"
        @click="startExport"
      >
        开始导出
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
h4 {
  margin: 0 0 12px;
}

.export-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.export-card {
  position: relative;
  display: flex;
  min-height: 92px;
  flex-direction: column;
  gap: 8px;
  justify-content: center;
  padding: 16px;
  color: #1f2d3d;
  text-align: left;
  background: #fff;
  border: 1px solid #d7e0ee;
  border-radius: 10px;
  cursor: pointer;
}

.export-card.selected {
  background: #fff1f2;
  border-color: #ed2437;
}

.export-card small {
  color: #7d8ca1;
}

.export-card span {
  position: absolute;
  right: 16px;
  bottom: 16px;
  display: grid;
  width: 22px;
  height: 22px;
  place-items: center;
  color: #fff;
  background: #ed2437;
  border-radius: 50%;
}

.scope-alert,
.export-progress {
  margin-top: 16px;
}

.export-progress {
  padding: 14px;
  background: #f7f9fc;
  border-radius: 8px;
}

.export-progress p {
  margin: 8px 0 0;
  color: #52657a;
  font-size: 13px;
}
</style>
