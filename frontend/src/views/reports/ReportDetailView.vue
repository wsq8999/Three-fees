<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  onUpdated,
  reactive,
  ref,
} from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowLeft, Download } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";

import { businessApi, triggerBrowserDownload } from "@/api/business-api";
import OverLimitRatioTags from "@/components/business/OverLimitRatioTags.vue";
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
const errorMessage = ref("");
const previewContentRef = ref<HTMLElement>();
const metadataRef = ref<HTMLElement>();
const metadataRowRef = ref<HTMLElement>();
const metadataScale = ref(1);
const correctionVisible = ref(false);
const correctionError = ref("");
const correctionForm = reactive({
  reason: "",
});
let previewImageCleanup: Array<() => void> = [];
let metadataResizeObserver: ResizeObserver | null = null;

const isHistorical = computed(
  () => report.value?.source === "HISTORICAL_IMPORT",
);
const sourceLabel = computed(() =>
  isHistorical.value ? "历史导入" : "系统生成",
);
const billingPointLabel = computed(() => {
  if (report.value === null) return "—";
  return `${report.value.billingPointCode} ｜ ${report.value.billingPointName} ｜ ${report.value.city.name} ｜ ${report.value.period}`;
});
const rawContent = computed(
  () => report.value?.previewHtml || report.value?.summary || "",
);
const normalizedContent = computed(() =>
  normalizePreviewContent(rawContent.value),
);
const contentHtml = computed(() =>
  looksLikeHtml(normalizedContent.value) ? normalizedContent.value : "",
);
const reportSections = computed(() => {
  if (contentHtml.value) return [];
  const summary = normalizedContent.value.trim();
  if (!summary) return [];
  const parts = summary
    .split(/\n{2,}|\r?\n(?=[一二三四五六七八九十]+[、.．])/)
    .map((item) => item.trim())
    .filter(Boolean);
  if (parts.length <= 1) {
    return [{ title: "报告正文", content: summary }];
  }
  return parts.map((content, index) => ({
    title: `第 ${index + 1} 部分`,
    content,
  }));
});

function normalizePreviewContent(value: string): string {
  const decoded = decodeHtmlEntities(value.trim());
  return decoded
    .replace(/Historical Word preview/gi, "")
    .replace(/Original Word file is the source of truth\./gi, "")
    .replace(/\bINCLUDEPICTURE\b[\s\S]*?\bMERGEFORMAT(?:INET)?\b/gi, "")
    .replace(
      /\b(?:MERGEFORMATINET|MERGEFORMAT|INCLUDEPICTURE|HYPERLINK)\b/gi,
      "",
    )
    .trim();
}

function clearPreviewImageHandlers(): void {
  previewImageCleanup.forEach((cleanup) => cleanup());
  previewImageCleanup = [];
}

function reportImageUrl(id: string): string {
  return `/api/v1/files/${encodeURIComponent(id)}?inline=true`;
}

function normalizeReportImageSrc(image: HTMLImageElement): string | null {
  const fileId =
    image.dataset.fileId ??
    image.closest<HTMLElement>("[data-file-id]")?.dataset.fileId;
  if (fileId !== undefined && fileId.length > 0) return reportImageUrl(fileId);
  const rawSrc = image.getAttribute("src")?.trim();
  if (!rawSrc) return null;
  try {
    const url = new URL(rawSrc, window.location.origin);
    if (
      url.origin === window.location.origin &&
      url.pathname.startsWith("/api/v1/files/")
    ) {
      url.searchParams.set("inline", "true");
      return `${url.pathname}${url.search}${url.hash}`;
    }
  } catch {
    return rawSrc;
  }
  return rawSrc;
}

function showImageFallback(image: HTMLImageElement): void {
  if (image.dataset.fallbackRendered === "true") return;
  image.dataset.fallbackRendered = "true";
  const fallback = document.createElement("div");
  fallback.className = "report-image-fallback";
  fallback.textContent = "图片暂无法显示，请下载 Word 查看原图。";
  image.replaceWith(fallback);
}

function enhanceReportPreviewImages(): void {
  clearPreviewImageHandlers();
  const root = previewContentRef.value;
  if (root === undefined) return;
  root.querySelectorAll<HTMLImageElement>("img").forEach((image) => {
    const normalizedSrc = normalizeReportImageSrc(image);
    if (normalizedSrc === null) {
      showImageFallback(image);
      return;
    }
    if (image.getAttribute("src") !== normalizedSrc) {
      image.src = normalizedSrc;
    }
    image.alt ||= "稽核报告图片";
    image.loading = "lazy";
    image.decoding = "async";
    const onError = (): void => showImageFallback(image);
    image.addEventListener("error", onError, { once: true });
    previewImageCleanup.push(() => image.removeEventListener("error", onError));
    if (image.complete && image.naturalWidth === 0) showImageFallback(image);
  });
}

function decodeHtmlEntities(value: string): string {
  return value
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, "&");
}

function looksLikeHtml(value: string): boolean {
  return /<\/?(div|p|table|tr|td|th|figure|img|section|article|h[1-6]|ul|ol|li)\b/i.test(
    value,
  );
}

async function load(): Promise<void> {
  loading.value = true;
  errorMessage.value = "";
  try {
    const loaded = await businessApi.reports.get(String(route.params.reportId));
    if (loaded === undefined) throw new Error("报告不存在或当前账号无权访问");
    report.value = loaded;
    loading.value = false;
    await nextTick();
    enhanceReportPreviewImages();
    if (props.correction) correctionVisible.value = true;
    if (route.query.autoDownload === "word") {
      await downloadWord();
      const query = { ...route.query };
      delete query.autoDownload;
      await router.replace({
        name: route.name ?? "report-detail",
        params: route.params,
        query,
      });
    }
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
    await triggerBrowserDownload(
      `/api/v1/reports/${encodeURIComponent(report.value.id)}/word`,
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
  if (route.name === "report-correction") {
    await router.replace({
      name: "report-detail",
      params: { reportId: route.params.reportId },
      query: route.query,
    });
  }
}

async function beginCorrection(): Promise<void> {
  correctionError.value = "";
  if (!correctionForm.reason.trim()) {
    correctionError.value = "请填写更正原因。";
    return;
  }
  if (report.value === null) return;
  correctionVisible.value = false;
  const draft = await businessApi.drafts.createCorrection(
    report.value.id,
    correctionForm.reason.trim(),
  );
  await router.push({
    name: "report-draft",
    params: { draftId: draft.id },
    query: { from: route.fullPath },
  });
}

function correctionDialogChanged(visible: boolean): void {
  if (!visible) void closeCorrection();
}

async function goBack(): Promise<void> {
  await router.push(
    typeof route.query.from === "string"
      ? route.query.from
      : "/reports/history",
  );
}

function updateMetadataScale(): void {
  const container = metadataRef.value;
  const row = metadataRowRef.value;
  if (!container || !row) return;
  requestAnimationFrame(() => {
    const available = container.clientWidth;
    const needed = row.scrollWidth;
    const nextScale = needed > available && available > 0
      ? available / needed
      : 1;
    if (Math.abs(metadataScale.value - nextScale) > 0.001) {
      metadataScale.value = nextScale;
    }
  });
}

onMounted(() => {
  void load();
  metadataResizeObserver = new ResizeObserver(updateMetadataScale);
  if (metadataRef.value) {
    metadataResizeObserver.observe(metadataRef.value);
  }
  void nextTick(updateMetadataScale);
});
onUpdated(() => {
  void nextTick(enhanceReportPreviewImages);
  void nextTick(updateMetadataScale);
});
onBeforeUnmount(() => {
  clearPreviewImageHandlers();
  metadataResizeObserver?.disconnect();
  metadataResizeObserver = null;
});
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
    <section ref="metadataRef" class="report-metadata business-card">
      <div
        ref="metadataRowRef"
        class="report-metadata-row"
        :style="{ transform: `scale(${metadataScale})` }"
      >
        <div>
          <small>报账点：</small>
          <strong>{{ billingPointLabel }}</strong>
        </div>
        <div>
          <small>超标比例：</small>
          <OverLimitRatioTags :ratios="report.overLimitRatios" />
        </div>
        <div>
          <small>报告来源：</small>
          <strong>{{ sourceLabel }}</strong>
        </div>
      </div>
    </section>

    <section class="report-preview business-card" aria-label="报告预览">
      <article class="report-sheet">
        <article
          v-if="contentHtml"
          ref="previewContentRef"
          class="word-preview-content"
          v-html="contentHtml"
        />
        <template v-else>
          <section v-for="section in reportSections" :key="section.title">
            <h2>{{ section.title }}</h2>
            <p>{{ section.content }}</p>
          </section>
        </template>
        <ElEmpty
          v-if="!contentHtml && reportSections.length === 0"
          description="当前报告暂无法完整在线预览，请下载原始 Word 查看。"
        />
        <aside v-if="report.corrections.length" class="correction-note">
          <strong>更正记录</strong>
          <p v-for="item in report.corrections" :key="item.occurredAt">
            {{ item.occurredAt }} ｜ {{ item.reason }} ｜ {{ item.summary }}
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
      title="更正报告"
      width="min(520px, calc(100vw - 32px))"
      append-to-body
      align-center
      destroy-on-close
      @update:model-value="correctionDialogChanged"
    >
      <ElAlert
        title="点击下一步后将进入生成报告页面，原报告内容会自动回显。重新生成后，报告详情只展示最新内容。"
        type="info"
        show-icon
        :closable="false"
      />
      <ElForm class="correction-form" label-position="top">
        <ElFormItem label="更正原因" required>
          <ElInput
            v-model="correctionForm.reason"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            placeholder="请填写本次更正原因"
          />
        </ElFormItem>
      </ElForm>
      <ElAlert
        v-if="correctionError"
        :title="correctionError"
        type="error"
        show-icon
      />
      <template #footer>
        <ElButton @click="closeCorrection">取消</ElButton>
        <ElButton type="primary" @click="beginCorrection">下一步</ElButton>
      </template>
    </ElDialog>
  </template>
</template>

<style scoped>
.report-metadata {
  display: flex;
  align-items: center;
  padding: 14px 18px;
  margin-bottom: 16px;
  overflow: hidden;
}

.report-metadata-row {
  display: flex;
  gap: clamp(18px, 4vw, 56px);
  align-items: center;
  width: max-content;
  max-width: none;
  white-space: nowrap;
  transform-origin: left center;
}

.report-metadata-row > div {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
  align-items: center;
  white-space: nowrap;
}

.report-metadata-row > div:first-child {
  min-width: 620px;
}

.report-metadata-row > div:nth-child(2) {
  min-width: 320px;
}

.report-metadata-row > div:nth-child(3) {
  min-width: 170px;
}

.report-metadata small {
  flex: 0 0 auto;
  color: #7d8ca1;
  font-weight: 700;
}

.report-metadata strong {
  flex: 0 0 auto;
  color: #001733;
}

.report-metadata :deep(.el-tag) {
  flex: 0 0 auto;
}

.over-limit-value {
  color: #f5223d !important;
}

.report-preview {
  padding: 0;
  overflow: auto;
}

.report-sheet {
  width: min(960px, 100%);
  min-height: 620px;
  padding: 48px 56px;
  margin: 0 auto;
  color: #001733;
  line-height: 1.9;
  background: #fff;
}

.report-sheet :deep(h1) {
  margin: 0 0 28px;
  text-align: center;
  font-size: 24px;
}

.report-sheet h2,
.report-sheet :deep(h2) {
  margin: 24px 0 10px;
  font-size: 18px;
}

.report-sheet p,
.report-sheet :deep(p) {
  margin: 8px 0;
  white-space: pre-wrap;
}

.word-preview-content :deep(table) {
  width: 100%;
  margin: 12px 0;
  border-collapse: collapse;
}

.word-preview-content :deep(td),
.word-preview-content :deep(th) {
  padding: 6px 8px;
  border: 1px solid #d8e0eb;
}

.word-preview-content :deep(img) {
  display: block;
  max-width: 100%;
  height: auto;
  margin: 12px auto;
  object-fit: contain;
}

.word-preview-content :deep(.report-image-fallback) {
  padding: 16px;
  margin: 12px 0;
  color: #7d4b00;
  text-align: center;
  background: #fff7e6;
  border: 1px dashed #f0b35a;
  border-radius: 6px;
}

.correction-note {
  padding: 12px 14px;
  margin-top: 24px;
  color: #52627a;
  background: #f6f8fb;
  border: 1px solid #e7edf5;
  border-radius: 6px;
}

.report-actions {
  position: sticky;
  bottom: 0;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 0 0;
  background: #f6f8fb;
}

.correction-form {
  margin-top: 14px;
}

@media (max-width: 960px) {
  .report-metadata {
    gap: 12px;
  }

  .report-sheet {
    padding: 28px 20px;
  }
}
</style>
