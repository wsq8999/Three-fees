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
import { Download } from "@element-plus/icons-vue";
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
const contentHtml = computed(() => {
  if (!looksLikeHtml(normalizedContent.value)) return "";
  return isHistorical.value
    ? normalizeHistoricalPreviewHtml(normalizedContent.value)
    : emphasizeReportCauses(normalizedContent.value);
});
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

function emphasizeReportCauses(html: string): string {
  const template = document.createElement("template");
  template.innerHTML = normalizeReportHtmlWhitespace(html);
  normalizeReportEmphasis(template.content);
  emphasizeTextNodes(template.content);
  return template.innerHTML;
}

function emphasizeTextNodes(root: DocumentFragment): void {
  const reasonPattern = reportReasonPattern();
  const causeLabelPattern = causeLabelPatternForText();
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const textNodes: Text[] = [];
  while (walker.nextNode()) {
    const node = walker.currentNode;
    if (!(node instanceof Text)) continue;
    const parent = node.parentElement;
    if (
      parent?.closest("strong,b,figure,table,script,style") !== null ||
      (!reasonPattern.test(node.data) && !causeLabelPattern.test(node.data))
    ) {
      reasonPattern.lastIndex = 0;
      causeLabelPattern.lastIndex = 0;
      continue;
    }
    reasonPattern.lastIndex = 0;
    causeLabelPattern.lastIndex = 0;
    textNodes.push(node);
  }
  textNodes.forEach((node) => {
    node.replaceWith(emphasizedTextFragment(node.data));
  });
}

function reportReasonPattern(): RegExp {
  return new RegExp(
    [
      "资管系统未及时更新[^。；;\\n]*(?:[。；;]|$)",
      "额定功率台账未及时更新[^。；;\\n]*(?:[。；;]|$)",
      "实际用电情况正常",
      "极简站改造新增机柜及空调长时间运行所致",
      "不存在用电量跑冒滴漏现象，不存在偷搭电问题",
      "不存在用电量跑冒滴漏",
      "不存在跑冒滴漏",
      "不存在偷搭电",
      "分摊比例变化[^。；;\\n]*(?:[。；;]|$)",
      "电信下电退出分摊[^。；;\\n]*(?:[。；;]|$)",
      "电信设备已下电退出电费分摊[^。；;\\n]*(?:[。；;]|$)",
      "设备新增[^。；;\\n]*(?:[。；;]|$)",
      "站址搬迁[^。；;\\n]*(?:[。；;]|$)",
      "合并电表[^。；;\\n]*(?:[。；;]|$)",
      "空调长时间运行[^。；;\\n]*(?:[。；;]|$)",
    ].join("|"),
    "g",
  );
}

function causeLabelPatternForText(): RegExp {
  return /(?:本期(?:电量)?(?:同比|环比|额定(?:标杆)?)超标原因|超标原因(?:是|为)?)[：:，,]*/g;
}

function emphasizedTextFragment(text: string): DocumentFragment {
  const fragment = document.createDocumentFragment();
  const reasonPattern = reportReasonPattern();
  const causeLabelPattern = causeLabelPatternForText();
  let index = 0;
  while (index < text.length) {
    reasonPattern.lastIndex = index;
    causeLabelPattern.lastIndex = index;
    const reasonMatch = reasonPattern.exec(text);
    const labelMatch = causeLabelPattern.exec(text);
    const next =
      labelMatch !== null &&
      (reasonMatch === null || labelMatch.index <= reasonMatch.index)
        ? { type: "label" as const, index: labelMatch.index, text: labelMatch[0] }
        : reasonMatch !== null
          ? { type: "reason" as const, index: reasonMatch.index, text: reasonMatch[0] }
          : null;
    if (next === null) {
      fragment.append(document.createTextNode(text.slice(index)));
      break;
    }
    if (next.index > index) {
      fragment.append(document.createTextNode(text.slice(index, next.index)));
    }
    if (next.type === "label") {
      fragment.append(document.createTextNode(next.text));
      const start = next.index + next.text.length;
      const end = sentenceEnd(text, start);
      const cause = text.slice(start, end).trimStart();
      if (cause.length > 0 && !isMetricOnlyText(cause)) {
        const leading = text.slice(start, end).match(/^\s*/)?.[0] ?? "";
        if (leading) fragment.append(document.createTextNode(leading));
        appendStrong(fragment, cause);
      } else {
        fragment.append(document.createTextNode(text.slice(start, end)));
      }
      index = end;
    } else {
      appendStrong(fragment, next.text);
      index = next.index + next.text.length;
    }
  }
  return fragment;
}

function sentenceEnd(text: string, start: number): number {
  for (let index = start; index < text.length; index += 1) {
    if ("。；;\n".includes(text[index] ?? "")) return index + 1;
  }
  return text.length;
}

function appendStrong(fragment: DocumentFragment, text: string): void {
  const strong = document.createElement("strong");
  strong.className = "report-cause-emphasis";
  strong.textContent = text;
  fragment.append(strong);
}

function isMetricOnlyText(text: string): boolean {
  return /(?:本期日均|正常上限|超标\d+(?:\.\d+)?%|超标比例)/.test(text) && !reportReasonPattern().test(text);
}

function normalizeReportEmphasis(root: DocumentFragment): void {
  root.querySelectorAll<HTMLElement>("strong,b").forEach((element) => {
    const text = normalizeReportText(element.textContent ?? "");
    const hasReason = reportReasonPattern().test(text);
    const isMetricOnly = isMetricOnlyText(text) && !hasReason;
    const wrapsCauseLabel = /本期(?:电量)?(?:同比|环比|额定(?:标杆)?)超标原因[：:]/.test(text);
    if (!isMetricOnly && !wrapsCauseLabel) return;
    element.replaceWith(document.createTextNode(text));
  });
}

function normalizeReportText(value: string): string {
  return value.replace(/[\u00a0\u3000]/g, " ").replace(/[ \t\v\f]+/g, " ").trim();
}

function normalizeReportHtmlWhitespace(value: string): string {
  return value
    .replace(/&nbsp;/gi, " ")
    .replace(/[\u00a0\u3000]/g, " ")
    .replace(/[ \t\v\f]{2,}/g, " ")
    .replace(/(?:<br\s*\/?>\s*){2,}/gi, "<br>");
}

function normalizeHistoricalPreviewHtml(value: string): string {
  const template = document.createElement("template");
  template.innerHTML = normalizeReportHtmlWhitespace(value);
  template.content.querySelectorAll<HTMLElement>("img,figure").forEach((element) => {
    element.removeAttribute("data-display-width");
    element.removeAttribute("data-display-height");
    element.removeAttribute("width");
    element.removeAttribute("height");
    removeSizingStyle(element);
  });
  template.content.querySelectorAll<HTMLElement>("p").forEach((paragraph) => {
    const hasVisibleContent =
      (paragraph.textContent ?? "").trim().length > 0 ||
      paragraph.querySelector("img,table") !== null;
    if (!hasVisibleContent) paragraph.remove();
  });
  return template.innerHTML;
}

function removeSizingStyle(element: HTMLElement): void {
  const rawStyle = element.getAttribute("style");
  if (rawStyle === null) return;
  const kept = rawStyle
    .split(";")
    .map((item) => item.trim())
    .filter(Boolean)
    .filter((item) => !/^(?:min-|max-)?(?:width|height)\s*:/i.test(item))
    .join(";");
  if (kept.length > 0) {
    element.setAttribute("style", kept);
  } else {
    element.removeAttribute("style");
  }
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
    if (!isHistorical.value) reconcilePreviewImageSize(image);
    const onError = (): void => showImageFallback(image);
    const onLoad = (): void => {
      if (!isHistorical.value) reconcilePreviewImageSize(image);
    };
    image.addEventListener("error", onError, { once: true });
    image.addEventListener("load", onLoad, { once: true });
    previewImageCleanup.push(() => image.removeEventListener("error", onError));
    previewImageCleanup.push(() => image.removeEventListener("load", onLoad));
    if (image.complete && image.naturalWidth === 0) showImageFallback(image);
  });
}

function reconcilePreviewImageSize(image: HTMLImageElement): void {
  if (image.naturalWidth <= 0 || image.naturalHeight <= 0) return;
  const width =
    parseCssPixelValue(image.dataset.displayWidth ?? null) ??
    parseCssPixelValue(image.getAttribute("width")) ??
    parseCssPixelValue(cssProperty(image.getAttribute("style"), "width"));
  if (width === undefined) return;
  const height = Math.round((width * image.naturalHeight * 100) / image.naturalWidth) / 100;
  image.dataset.displayWidth = String(width);
  image.dataset.displayHeight = String(height);
  image.setAttribute("width", String(Math.round(width)));
  image.setAttribute("height", String(Math.round(height)));
  image.style.width = `${width}px`;
  image.style.height = `${height}px`;
}

function parseCssPixelValue(value: string | null): number | undefined {
  if (value === null || value.trim().length === 0) return undefined;
  const trimmed = value.trim().toLowerCase();
  const match = trimmed.match(/^([0-9]+(?:\.[0-9]+)?)(px|pt|in|cm|mm)?$/);
  if (match === null) return undefined;
  const numeric = Number(match[1]);
  if (!Number.isFinite(numeric) || numeric <= 0) return undefined;
  const unit = match[2] ?? "px";
  if (unit === "pt") return numeric / 0.75;
  if (unit === "in") return numeric * 96;
  if (unit === "cm") return (numeric / 2.54) * 96;
  if (unit === "mm") return (numeric / 25.4) * 96;
  return numeric;
}

function cssProperty(style: string | null, name: string): string | null {
  if (style === null || style.trim().length === 0) return null;
  for (const part of style.split(";")) {
    const [key, ...rest] = part.split(":");
    if (key?.trim().toLowerCase() === name) return rest.join(":").trim();
  }
  return null;
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
  const fromQuery = { ...route.query };
  delete fromQuery.autoDownload;
  await router.push({
    name: "report-draft",
    params: { draftId: draft.id },
    query: {
      from: router.resolve({
        name: "report-detail",
        params: { reportId: report.value.id },
        query: fromQuery,
      }).fullPath,
    },
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
          :class="{ 'word-preview-content--historical': isHistorical }"
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
      <ElButton @click="goBack">返回</ElButton>
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
  font-weight: 800;
}

.report-sheet h2,
.report-sheet :deep(h2) {
  margin: 26px 0 12px;
  font-size: 18px;
  font-weight: 800;
}

.report-sheet p,
.report-sheet :deep(p) {
  margin: 10px 0;
  text-indent: 2em;
  white-space: pre-wrap;
}

.report-sheet :deep(strong),
.report-sheet :deep(b),
.word-preview-content :deep(strong),
.word-preview-content :deep(b) {
  font-weight: 800;
}

.word-preview-content :deep(p) {
  margin: 10px 0;
  text-indent: 2em;
  white-space: pre-wrap;
}

.word-preview-content :deep(p[style*="text-align:center"]),
.word-preview-content :deep(p[style*="text-align: center"]) {
  text-indent: 0;
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
  height: auto !important;
  margin: 12px auto;
  object-fit: contain;
}

.word-preview-content:not(.word-preview-content--historical) :deep(img:not([height]):not([style*="height"])) {
  height: auto !important;
}

.word-preview-content--historical :deep(img) {
  display: inline-block;
  max-width: 100%;
  height: auto !important;
  margin: 0 8px 8px 0;
  vertical-align: top;
  object-fit: contain;
}

.word-preview-content--historical :deep(.word-inline-image) {
  display: inline-block;
  max-width: 100%;
  vertical-align: top;
}

.word-preview-content :deep(.inline-image-row) {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: flex-start;
  max-width: 100%;
  margin: 8px 0;
}

.word-preview-content :deep(.inline-image-row figure),
.word-preview-content :deep(.inline-image-row img) {
  display: inline-block;
  max-width: 100%;
  margin: 0;
  vertical-align: top;
}

.report-sheet .word-preview-content--historical :deep(p) {
  text-indent: 0;
}

.report-sheet .word-preview-content--historical :deep(.word-preview > p:first-child) {
  text-align: center;
  text-indent: 0;
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
