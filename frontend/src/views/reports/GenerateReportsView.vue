<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
import { ArrowLeft, ChatLineRound, Download, Picture, Refresh } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";

import { businessApi, triggerBrowserDownload } from "@/api/business-api";
import PageState from "@/components/PageState.vue";
import { useSessionStore } from "@/stores/session";
import type { ReportGenerationCandidate, ReportGenerationImageInput } from "@/types/business";
import { standardConfirm } from "@/utils/message-box";

const route = useRoute();
const router = useRouter();
const session = useSessionStore();

const loading = ref(true);
const initializing = ref(false);
const generating = ref(false);
const analyzing = ref(false);
const errorMessage = ref("");
const candidates = ref<ReportGenerationCandidate[]>([]);
const selectedKey = ref("");
const selected = ref<ReportGenerationCandidate | null>(null);
const contentHtml = ref("");
const editorRef = ref<HTMLElement>();
const generated = ref(false);
const suppressLeaveConfirm = ref(false);
const analysisDialogVisible = ref(false);
const analysisFiles = ref<File[]>([]);
const analysisInstruction = ref("");
const aiAnswer = ref("");

const correctionReportId = computed(() => String(route.query.reportId ?? "").trim());
const correctionReason = computed(() => String(route.query.reason ?? "").trim());
const isCorrection = computed(() => correctionReportId.value.length > 0);

const candidateOptions = computed(() =>
  candidates.value.map((item) => ({
    key: candidateKey(item),
    label: `${item.billingPointCode} ｜ ${item.billingPointName} ｜ ${item.cityName} ｜ ${item.period}`,
    item,
  })),
);

const overLimitType = computed(() => selected.value?.overLimitType ?? "—");
const exceedRatio = computed(() => formatRatio(selected.value?.maxExceedRatio));
const selectedOptionLabel = computed(
  () => candidateOptions.value.find((option) => option.key === selectedKey.value)?.label ?? "",
);
const selectedOptionWidth = computed(() => {
  const label = selectedOptionLabel.value || "请选择报账点";
  const textWidth = Array.from(label).reduce(
    (total, char) => total + (char.charCodeAt(0) > 255 ? 18 : 10),
    0,
  );
  return `${Math.max(760, textWidth + 160)}px`;
});
const hasUnsavedContent = computed(
  () => !generated.value && Boolean(selected.value) && Boolean(editorRef.value?.innerHTML.trim()),
);

function candidateKey(item: ReportGenerationCandidate): string {
  return `${item.billingPointCode}@@${item.period}`;
}

function formatRatio(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === "") return "—";
  const numeric = Number(value);
  if (Number.isFinite(numeric)) return `${numeric.toFixed(2)}%`;
  const text = String(value);
  return text.endsWith("%") ? text : `${text}%`;
}

function currentQueryTarget(): { billingPointCode: string; period: string } | null {
  const billingPointCode = String(route.query.billingPointCode ?? "").trim();
  const period = String(route.query.period ?? "").trim();
  if (!billingPointCode || !period) return null;
  return { billingPointCode, period };
}

async function load(): Promise<void> {
  loading.value = true;
  errorMessage.value = "";
  try {
    if (isCorrection.value) {
      const result = await businessApi.reportGeneration.correctionInitialContent(
        correctionReportId.value,
      );
      candidates.value = [result.candidate];
      selected.value = result.candidate;
      selectedKey.value = candidateKey(result.candidate);
      contentHtml.value = result.contentHtml;
      generated.value = false;
      await renderContent();
      return;
    }

    candidates.value = await businessApi.reportGeneration.candidates(
      session.currentUser?.city?.code ?? "",
    );
    const target = currentQueryTarget();
    const option = target
      ? candidates.value.find(
          (item) =>
            item.billingPointCode === target.billingPointCode && item.period === target.period,
        )
      : candidates.value[0];
    if (option) {
      selectedKey.value = candidateKey(option);
      selected.value = option;
      if (target) await initialize(option);
    } else {
      selected.value = null;
      contentHtml.value = "";
      aiAnswer.value = "";
      await renderContent();
    }
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "待生成报告加载失败";
  } finally {
    loading.value = false;
  }
}

async function initialize(candidate: ReportGenerationCandidate): Promise<void> {
  if (isCorrection.value) return;
  initializing.value = true;
  try {
    const draft = await businessApi.drafts.createOrResume(candidate.billingPointPeriodId);
    suppressLeaveConfirm.value = true;
    await router.push({
      name: "report-draft",
      params: { draftId: draft.id },
      query: {
        from: typeof route.query.from === "string" ? route.query.from : "/reports/generate",
      },
    });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "报告初始化失败");
  } finally {
    initializing.value = false;
  }
}

async function selectChanged(key: string): Promise<void> {
  const option = candidates.value.find((item) => candidateKey(item) === key);
  if (option) selected.value = option;
}

async function renderContent(): Promise<void> {
  await nextTick();
  if (editorRef.value) editorRef.value.innerHTML = contentHtml.value;
}

function syncContent(): void {
  contentHtml.value = editorRef.value?.innerHTML ?? "";
}

async function confirmDiscardCurrent(action: string): Promise<boolean> {
  if (!hasUnsavedContent.value) return true;
  try {
    await standardConfirm(
      `当前报告尚未生成，${action}后当前编辑的文字和图片将不会保存，是否继续？`,
      action,
      {
        type: "warning",
        confirmButtonText: action === "切换报账点" ? "确认切换" : "确认离开",
        cancelButtonText: "取消",
      },
    );
    return true;
  } catch {
    return false;
  }
}

async function pasteIntoEditor(event: ClipboardEvent): Promise<void> {
  const items = Array.from(event.clipboardData?.items ?? []);
  const imageItems = items.filter((item) => item.type.startsWith("image/"));
  if (imageItems.length === 0) return;
  event.preventDefault();
  for (const item of imageItems) {
    const file = item.getAsFile();
    if (!file) continue;
    const dataUrl = await fileToDataUrl(file);
    insertHtmlAtSelection(`<figure><img src="${dataUrl}" alt="现场图片" /></figure>`);
  }
  syncContent();
}

function fileToDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(file);
  });
}

function fileToBase64(file: File): Promise<string> {
  return fileToDataUrl(file).then((dataUrl) => dataUrl.split(",", 2)[1] ?? "");
}

function insertHtmlAtSelection(html: string): void {
  editorRef.value?.focus();
  const selection = window.getSelection();
  if (!selection || selection.rangeCount === 0) return;
  const range = selection.getRangeAt(0);
  range.deleteContents();
  const template = document.createElement("template");
  template.innerHTML = html;
  const fragment = template.content;
  const last = fragment.lastChild;
  range.insertNode(fragment);
  if (last) {
    range.setStartAfter(last);
    range.collapse(true);
    selection.removeAllRanges();
    selection.addRange(range);
  }
}

function openAnalysisDialog(): void {
  if (!selected.value) {
    ElMessage.warning("请先选择报账点。");
    return;
  }
  syncContent();
  if (!contentHtml.value.trim()) {
    ElMessage.warning("请先生成或填写报告正文。");
    return;
  }
  analysisFiles.value = [];
  analysisInstruction.value = "";
  analysisDialogVisible.value = true;
}

function selectAnalysisFiles(event: Event): void {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  input.value = "";
  if (!files.length) return;
  const accepted = files.filter((file) => ["image/png", "image/jpeg"].includes(file.type));
  if (accepted.length !== files.length) {
    ElMessage.warning("分析图片只支持 PNG 或 JPEG。");
  }
  const merged = [...analysisFiles.value, ...accepted].slice(0, 10);
  const totalBytes = merged.reduce((sum, file) => sum + file.size, 0);
  if (totalBytes > 20 * 1024 * 1024) {
    ElMessage.warning("分析图片总大小不能超过 20 MiB。");
    return;
  }
  if (merged.some((file) => file.size > 10 * 1024 * 1024)) {
    ElMessage.warning("单张分析图片不能超过 10 MiB。");
    return;
  }
  analysisFiles.value = merged;
}

function removeAnalysisFile(index: number): void {
  analysisFiles.value.splice(index, 1);
}

async function submitAnalysis(): Promise<void> {
  if (!selected.value || analyzing.value) return;
  if (analysisFiles.value.length === 0) {
    ElMessage.warning("请先选择需要分析的图片。");
    return;
  }
  syncContent();
  analyzing.value = true;
  try {
    const images: ReportGenerationImageInput[] = await Promise.all(
      analysisFiles.value.map(async (file) => ({
        fileName: file.name,
        mediaType: file.type as "image/png" | "image/jpeg",
        base64Data: await fileToBase64(file),
      })),
    );
    const result = await businessApi.reportGeneration.analyzeImages({
      billingPointCode: selected.value.billingPointCode,
      period: selected.value.period,
      contentHtml: contentHtml.value,
      instruction: analysisInstruction.value,
      images,
    });
    contentHtml.value = result.updatedContentHtml || contentHtml.value;
    aiAnswer.value = result.answer || result.analysisText || "图片分析已完成。";
    await renderContent();
    analysisDialogVisible.value = false;
    ElMessage.success("图片分析已完成");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "图片分析失败，请先人工编辑报告。");
  } finally {
    analyzing.value = false;
  }
}

async function generate(): Promise<void> {
  if (!selected.value || generating.value) return;
  syncContent();
  if (!contentHtml.value.trim()) {
    ElMessage.warning("报告正文不能为空");
    return;
  }
  generating.value = true;
  try {
    const report = isCorrection.value
      ? await businessApi.reportGeneration.regenerate(correctionReportId.value, {
          billingPointCode: selected.value.billingPointCode,
          period: selected.value.period,
          contentHtml: contentHtml.value,
          reason: correctionReason.value || "报告内容更正",
        })
      : await businessApi.reportGeneration.generate({
          billingPointCode: selected.value.billingPointCode,
          period: selected.value.period,
          contentHtml: contentHtml.value,
        });
    generated.value = true;
    suppressLeaveConfirm.value = true;
    if (!isCorrection.value) {
      candidates.value = candidates.value.filter((item) => candidateKey(item) !== selectedKey.value);
    }
    try {
      await triggerBrowserDownload(
        `/api/v1/reports/${encodeURIComponent(report.id)}/word`,
        report.wordFileName,
      );
    } catch (error) {
      ElMessage.warning(error instanceof Error ? error.message : "Word 自动下载失败，可在详情页手动下载。");
    }
    await router.push({
      name: "report-detail",
      params: { reportId: report.id },
      query: { from: "/reports/history" },
    });
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "正式报告生成失败");
  } finally {
    generating.value = false;
  }
}

function aiPrompt(): void {
  ElMessage.info("AI 对话入口已保留，当前请先使用“分析图片”或人工编辑正文。");
}

async function goBack(): Promise<void> {
  if (!(await confirmDiscardCurrent("离开页面"))) return;
  suppressLeaveConfirm.value = true;
  const from = typeof route.query.from === "string" ? route.query.from : "/dashboard";
  await router.push(from);
}

onBeforeRouteLeave(async () => {
  if (suppressLeaveConfirm.value) return true;
  return await confirmDiscardCurrent("离开页面");
});

onBeforeUnmount(() => {
  suppressLeaveConfirm.value = true;
});

onMounted(load);
</script>

<template>
  <PageState v-if="loading" kind="loading" />
  <PageState
    v-else-if="errorMessage"
    kind="error"
    title="待生成报告加载失败"
    :description="errorMessage"
    @retry="load"
  />

  <template v-else>
    <section class="generation-head business-card">
      <label>
        <small>报账点</small>
        <ElSelect
          v-model="selectedKey"
          filterable
          class="candidate-select"
          :style="{ '--candidate-select-width': selectedOptionWidth }"
          :loading="initializing"
          :disabled="isCorrection"
          placeholder="暂无待生成报告"
          @change="selectChanged"
        >
          <ElOption
            v-for="option in candidateOptions"
            :key="option.key"
            :label="option.label"
            :value="option.key"
          />
        </ElSelect>
      </label>
      <div>
        <small>超标类型</small>
        <strong>{{ overLimitType }}</strong>
      </div>
      <div>
        <small>超标率</small>
        <strong class="danger-text">{{ exceedRatio }}</strong>
      </div>
    </section>

    <section v-if="selected && isCorrection" class="generation-workspace">
      <article
        ref="editorRef"
        class="report-editor business-card"
        contenteditable="true"
        spellcheck="false"
        v-html="contentHtml"
        @input="syncContent"
        @paste="pasteIntoEditor"
      />
      <aside class="ai-card business-card">
        <ElButton :icon="Picture" :loading="analyzing" @click="openAnalysisDialog">
          分析图片
        </ElButton>
        <ElInput
          v-model="aiAnswer"
          type="textarea"
          :rows="8"
          placeholder="图片分析结果会显示在这里"
          readonly
        />
        <ElButton :icon="ChatLineRound" @click="aiPrompt">发送到 AI</ElButton>
      </aside>
    </section>

    <section v-else-if="selected" class="agent-entry business-card">
      <div>
        <h2>城市 AI 电费稽核助手</h2>
        <p>
          进入后左侧为完整报告，支持直接粘贴多张现场图片；右侧可以分析全部图片、查询本报账点历史、连续纠正原因。
          最终确认后，结论才会沉淀到当前城市自己的经验库。
        </p>
      </div>
      <ElButton type="primary" size="large" :loading="initializing" @click="initialize(selected)">
        进入 AI 稽核助手
      </ElButton>
    </section>

    <section v-else class="empty-card business-card">
      <ElEmpty description="当前暂无超标且未生成正式报告的报账点账期">
        <ElButton :icon="Refresh" type="primary" @click="load">重新加载</ElButton>
      </ElEmpty>
    </section>

    <footer class="generation-actions">
      <ElButton :icon="ArrowLeft" @click="goBack">返回</ElButton>
      <ElButton
        type="primary"
        :icon="Download"
        :loading="generating || initializing"
        :disabled="!selected"
        @click="isCorrection ? generate() : initialize(selected!)"
      >
        {{ isCorrection ? "重新生成报告" : "进入 AI 稽核助手" }}
      </ElButton>
    </footer>

    <ElDialog
      v-model="analysisDialogVisible"
      title="分析图片"
      width="min(560px, calc(100vw - 32px))"
      append-to-body
      align-center
      destroy-on-close
      class="analysis-dialog"
    >
      <div class="analysis-form">
        <label class="file-picker">
          <input
            type="file"
            multiple
            accept="image/png,image/jpeg"
            @change="selectAnalysisFiles"
          />
          <span>选择图片</span>
        </label>
        <ul v-if="analysisFiles.length" class="analysis-files">
          <li v-for="(file, index) in analysisFiles" :key="`${file.name}-${file.size}-${index}`">
            <span>{{ file.name }}</span>
            <ElButton link type="danger" @click="removeAnalysisFile(index)">删除</ElButton>
          </li>
        </ul>
        <ElInput
          v-model="analysisInstruction"
          type="textarea"
          :rows="4"
          placeholder="可填写补充要求，例如：根据现场图片补充排查分析"
        />
      </div>
      <template #footer>
        <ElButton @click="analysisDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="analyzing" @click="submitAnalysis">开始分析</ElButton>
      </template>
    </ElDialog>
  </template>
</template>

<style scoped>
.generation-head {
  display: grid;
  grid-template-columns: max-content max-content max-content;
  gap: 16px;
  align-items: center;
  padding: 16px 20px;
  margin-bottom: 16px;
  overflow-x: auto;
}

.generation-head label,
.generation-head div {
  display: flex;
  gap: 8px;
  align-items: center;
  white-space: nowrap;
}

.generation-head small {
  flex: 0 0 auto;
  color: #7d8ca1;
  font-weight: 700;
}

.generation-head small::after {
  content: "：";
}

.generation-head strong {
  color: #001733;
  white-space: nowrap;
}

.candidate-select {
  width: var(--candidate-select-width);
  min-width: 560px;
  max-width: calc(100vw - 520px);
}

.candidate-select :deep(.el-select__wrapper) {
  min-width: 100%;
}

.candidate-select :deep(.el-select__selected-item) {
  max-width: none;
}

.danger-text {
  color: #f5223d !important;
}

.generation-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 16px;
}

.report-editor {
  min-height: 680px;
  padding: 42px 52px;
  line-height: 1.9;
  color: #001733;
  outline: none;
}

.report-editor :deep(h1) {
  margin: 0 0 28px;
  text-align: center;
  font-size: 24px;
}

.report-editor :deep(h2) {
  margin: 24px 0 10px;
  font-size: 18px;
}

.report-editor :deep(img) {
  max-width: 100%;
  height: auto;
}

.ai-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-self: start;
  padding: 16px;
}

.empty-card {
  padding: 36px;
}

.agent-entry {
  display: flex;
  min-height: 260px;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  padding: 42px;
}

.agent-entry h2 {
  margin: 0 0 14px;
  color: #001733;
}

.agent-entry p {
  max-width: 760px;
  margin: 0;
  color: #52657a;
  line-height: 1.9;
}

.generation-actions {
  position: sticky;
  bottom: 0;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 0 0;
  background: #f6f8fb;
}

.analysis-form {
  display: grid;
  gap: 12px;
}

.file-picker {
  display: inline-flex;
  width: fit-content;
  cursor: pointer;
}

.file-picker input {
  display: none;
}

.file-picker span {
  padding: 8px 14px;
  color: #1f6feb;
  background: #eef6ff;
  border: 1px solid #b9dcff;
  border-radius: 6px;
}

.analysis-files {
  display: grid;
  gap: 6px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.analysis-files li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 10px;
  background: #f6f8fb;
  border-radius: 6px;
}

@media (max-width: 1024px) {
  .generation-workspace {
    grid-template-columns: 1fr;
  }

  .candidate-select {
    min-width: 420px;
    max-width: calc(100vw - 180px);
  }
}

@media (max-width: 640px) {
  .generation-head {
    grid-template-columns: 1fr;
  }

  .candidate-select {
    width: 100%;
    min-width: 0;
    max-width: 100%;
  }

  .report-editor {
    min-height: 520px;
    padding: 24px 18px;
  }
}
</style>
