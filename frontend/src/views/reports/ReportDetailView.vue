<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowLeft, Download } from "@element-plus/icons-vue";
import type { UploadFile } from "element-plus";
import { ElMessage } from "element-plus";

import { businessApi, saveBlob } from "@/api/business-api";
import StatusTag from "@/components/business/StatusTag.vue";
import PageState from "@/components/PageState.vue";
import type { ReportSummary } from "@/types/business";

const props = withDefaults(defineProps<{ correction?: boolean }>(), {
  correction: false,
});

const route = useRoute();
const router = useRouter();

const report = ref<ReportSummary | null>(null);
const loading = ref(true);
const downloading = ref(false);
const correcting = ref(false);
const errorMessage = ref("");
const correctionVisible = ref(false);
const editorVisible = ref(false);
const correctionError = ref("");
const correctionForm = reactive({
  reason: "",
  file: null as File | null,
  summary: "",
});

const isHistorical = computed(
  () => report.value?.source === "HISTORICAL_IMPORT",
);

const reportSections = computed(() => {
  const summary = report.value?.summary?.trim() ?? "";
  if (!summary) return [];
  const parts = summary
    .split(/\n{2,}|\r?\n(?=[一二三四五六七八九十]、)/)
    .map((item) => item.trim())
    .filter(Boolean);
  if (parts.length <= 1) {
    return [{ title: "报告正文", content: summary }];
  }
  return parts.map((content, index) => ({
    title: `第${index + 1}部分`,
    content,
  }));
});

async function load(): Promise<void> {
  loading.value = true;
  errorMessage.value = "";
  try {
    const loaded = await businessApi.reports.get(String(route.params.reportId));
    if (loaded === undefined) throw new Error("报告不存在或当前账号无权访问");
    report.value = loaded;
    correctionForm.summary = loaded.summary;
    if (props.correction) correctionVisible.value = true;
  } catch (error) {
    errorMessage.value =
      error instanceof Error ? error.message : "报告加载失败";
  } finally {
    loading.value = false;
  }
}

async function downloadWord(): Promise<void> {
  if (report.value === null) return;
  downloading.value = true;
  try {
    saveBlob(
      await businessApi.reports.downloadWord(report.value.id),
      report.value.wordFileName,
    );
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "Word 下载失败");
  } finally {
    downloading.value = false;
  }
}

function openCorrection(): void {
  correctionForm.reason = "";
  correctionForm.file = null;
  correctionForm.summary = report.value?.summary ?? "";
  correctionError.value = "";
  correctionVisible.value = true;
  void router.replace({
    name: "report-correction",
    params: { reportId: route.params.reportId },
    query: route.query,
  });
}

async function closeCorrection(): Promise<void> {
  correctionVisible.value = false;
  editorVisible.value = false;
  if (route.name === "report-correction") {
    await router.replace({
      name: "report-detail",
      params: { reportId: route.params.reportId },
      query: route.query,
    });
  }
}

function correctionFileChanged(file: UploadFile): void {
  const raw = file.raw;
  if (raw === undefined) return;
  if (!/\.docx?$/i.test(raw.name)) {
    correctionError.value = "更正版报告只支持 .doc 或 .docx 文件。";
    correctionForm.file = null;
    return;
  }
  correctionError.value = "";
  correctionForm.file = raw;
}

async function beginCorrection(): Promise<void> {
  correctionError.value = "";
  if (!correctionForm.reason.trim()) {
    correctionError.value = "请填写更正原因。";
    return;
  }
  if (isHistorical.value && correctionForm.file === null) {
    correctionError.value = "人工导入报告必须上传更正版 Word 文件。";
    return;
  }
  if (!isHistorical.value) {
    correctionVisible.value = false;
    editorVisible.value = true;
    return;
  }
  await submitCorrection();
}

async function submitCorrection(): Promise<void> {
  if (report.value === null) return;
  correcting.value = true;
  correctionError.value = "";
  try {
    const beforeNumber = report.value.reportNumber;
    report.value = await businessApi.reports.correct(
      report.value.id,
      isHistorical.value
        ? {
            reason: correctionForm.reason,
            file: correctionForm.file as File,
          }
        : {
            reason: correctionForm.reason,
            correctedSummary: correctionForm.summary,
          },
    );
    if (report.value.reportNumber !== beforeNumber) {
      throw new Error("报告编号发生变化，已阻止错误结果展示");
    }
    await closeCorrection();
    ElMessage.success("报告已更正，编号保持不变。");
  } catch (error) {
    correctionError.value =
      error instanceof Error ? error.message : "报告更正失败";
  } finally {
    correcting.value = false;
  }
}

function correctionDialogChanged(visible: boolean): void {
  if (!visible) void closeCorrection();
}

function editorDialogChanged(visible: boolean): void {
  if (!visible) void closeCorrection();
}

async function goBack(): Promise<void> {
  await router.push(
    typeof route.query.from === "string" ? route.query.from : "/reports/history",
  );
}

onMounted(load);
</script>

<template>
  <PageState v-if="loading" kind="loading" />
  <PageState
    v-else-if="errorMessage"
    kind="error"
    title="报告加载失败"
    :description="errorMessage"
    @retry="load"
  />

  <template v-else-if="report">
    <section class="report-metadata business-card">
      <div>
        <small>报告编号</small>
        <strong>{{ report.reportNumber }}</strong>
      </div>
      <div>
        <small>报账点编码</small>
        <strong>{{ report.billingPointCode }}</strong>
      </div>
      <div>
        <small>报账点名称</small>
        <strong>{{ report.billingPointName }}</strong>
      </div>
      <div>
        <small>所属区域</small>
        <strong>{{ report.city.name }} / {{ report.district ?? "—" }}</strong>
      </div>
      <div>
        <small>账期</small>
        <strong>{{ report.period }}</strong>
      </div>
      <div>
        <small>报告状态</small>
        <StatusTag :value="report.status" />
      </div>
    </section>

    <section class="report-preview business-card" aria-label="报告预览">
      <article class="report-sheet">
        <h1>{{ report.billingPointName }}电费稽核报告</h1>
        <section v-for="section in reportSections" :key="section.title">
          <h2>{{ section.title }}</h2>
          <p>{{ section.content }}</p>
        </section>
        <ElEmpty
          v-if="reportSections.length === 0"
          description="当前报告暂无正文内容"
        />
        <aside v-if="report.corrections.length" class="correction-note">
          <strong>更正记录</strong>
          <p v-for="item in report.corrections" :key="item.occurredAt">
            {{ item.occurredAt }} · {{ item.reason }} · {{ item.summary }}
          </p>
        </aside>
      </article>
    </section>

    <footer class="report-actions">
      <ElButton :icon="ArrowLeft" @click="goBack">返回</ElButton>
      <ElButton :icon="Download" :loading="downloading" @click="downloadWord">
        下载 Word
      </ElButton>
      <ElButton type="primary" @click="openCorrection">更正报告</ElButton>
    </footer>

    <ElDialog
      :model-value="correctionVisible"
      title="发起报告更正"
      width="min(520px, 92vw)"
      :close-on-click-modal="false"
      @update:model-value="correctionDialogChanged"
    >
      <ElForm label-position="left" label-width="120px">
        <ElFormItem label="当前报告编号">
          <ElInput :model-value="report.reportNumber" disabled />
        </ElFormItem>
        <ElFormItem label="报账点名称">
          <ElInput :model-value="report.billingPointName" disabled />
        </ElFormItem>
        <ElFormItem label="账期">
          <ElInput :model-value="report.period" disabled />
        </ElFormItem>
        <ElFormItem label="更正原因" required>
          <ElInput
            v-model="correctionForm.reason"
            type="textarea"
            :rows="5"
            maxlength="500"
            show-word-limit
            placeholder="请输入需要更正的原因"
          />
        </ElFormItem>
      </ElForm>

      <template v-if="isHistorical">
        <h4>上传更正版 Word</h4>
        <ElUpload
          drag
          :auto-upload="false"
          :limit="1"
          accept=".doc,.docx"
          :on-change="correctionFileChanged"
          :on-remove="() => (correctionForm.file = null)"
        >
          <strong>点击或拖拽上传更正版报告</strong>
          <small>仅支持 .doc / .docx</small>
        </ElUpload>
      </template>

      <ElAlert
        v-if="correctionError"
        :title="correctionError"
        type="error"
        show-icon
        :closable="false"
      />
      <template #footer>
        <ElButton @click="closeCorrection">取消</ElButton>
        <ElButton type="primary" :loading="correcting" @click="beginCorrection">
          下一步
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      :model-value="editorVisible"
      title="调整系统生成报告"
      width="min(760px, 94vw)"
      :close-on-click-modal="false"
      @update:model-value="editorDialogChanged"
    >
      <ElInput
        v-model="correctionForm.summary"
        type="textarea"
        :rows="14"
        maxlength="5000"
        show-word-limit
      />
      <ElAlert
        v-if="correctionError"
        :title="correctionError"
        type="error"
        show-icon
        :closable="false"
      />
      <template #footer>
        <ElButton @click="closeCorrection">取消</ElButton>
        <ElButton type="primary" :loading="correcting" @click="submitCorrection">
          保存更正
        </ElButton>
      </template>
    </ElDialog>
  </template>
</template>

<style scoped>
.report-metadata {
  display: grid;
  grid-template-columns: 1fr 1fr 1.5fr 1.3fr 0.9fr 0.8fr;
  gap: 16px;
  padding: 16px 24px;
  margin-bottom: 16px;
}

.report-metadata div {
  display: grid;
  min-width: 0;
  gap: 6px;
}

.report-metadata small {
  color: #7d8ca1;
  font-weight: 600;
}

.report-metadata strong {
  overflow: hidden;
  color: #1f2d3d;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.report-preview {
  min-height: calc(100vh - 210px);
  padding: 28px 36px 88px;
}

.report-sheet {
  max-width: 980px;
  margin: 0 auto;
  color: #1f2d3d;
}

.report-sheet h1 {
  margin: 0 0 28px;
  color: #101827;
  font-size: 24px;
  text-align: center;
}

.report-sheet h2 {
  margin: 24px 0 12px;
  color: #101827;
  font-size: 18px;
}

.report-sheet p {
  margin: 0;
  line-height: 2;
  white-space: pre-wrap;
}

.correction-note {
  padding: 16px;
  margin-top: 24px;
  background: #fff8e8;
  border: 1px solid #f5d898;
  border-radius: 10px;
}

.report-actions {
  position: fixed;
  right: 16px;
  bottom: 0;
  left: calc(var(--sidebar-width) + 16px);
  z-index: 50;
  display: flex;
  min-height: 58px;
  gap: 12px;
  align-items: center;
  justify-content: flex-end;
  padding: 10px 18px;
  background: rgb(255 255 255 / 96%);
  border-top: 1px solid #dfe5ec;
}

@media (width <= 1100px) {
  .report-metadata {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
