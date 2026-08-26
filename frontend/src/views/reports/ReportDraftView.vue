<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Promotion } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";

import { businessApi } from "@/api/business-api";
import { ApiProblem } from "@/api/problem-details";
import OverLimitRatioTags from "@/components/business/OverLimitRatioTags.vue";
import OverLimitTypeTags from "@/components/business/OverLimitTypeTags.vue";
import PageState from "@/components/PageState.vue";
import type { DraftBlock, ReportDraft } from "@/types/business";
import { standardConfirm } from "@/utils/message-box";

const route = useRoute();
const router = useRouter();

const draft = ref<ReportDraft | null>(null);
const loading = ref(true);
const saving = ref(false);
const sending = ref(false);
const chatSending = ref(false);
const analyzingImages = ref(false);
const imageUpdating = ref(false);
const generating = ref(false);
const errorMessage = ref("");
const prompt = ref("");
const assistantIntent = ref<"AUTO" | "ASK" | "EDIT" | "CORRECTION">("AUTO");
const assistantError = ref("");
const assistantVisible = ref(true);
const reportPaperRef = ref<HTMLElement>();
const htmlReportRef = ref<HTMLElement>();
const editorRenderKey = ref(0);
const pendingImageIds = ref<string[]>([]);
const uploadingImages = ref(false);
let saveInFlight: Promise<boolean> | null = null;
let analysisPollTimer: ReturnType<typeof window.setTimeout> | null = null;
let analysisPolling = false;
let selectedInlineFigure: HTMLElement | null = null;
let lastEditorRange: Range | null = null;
let inlineImageInteraction:
  | {
      mode: "pending";
      figure: HTMLElement;
      editor: HTMLElement;
      pointerId: number;
      startX: number;
      startY: number;
    }
  | {
      mode: "dragging";
      figure: HTMLElement;
      editor: HTMLElement;
      pointerId: number;
      marker: HTMLElement;
    }
  | {
      mode: "resizing";
      figure: HTMLElement;
      pointerId: number;
      startX: number;
      startWidth: number;
      aspectRatio: number;
      direction: -1 | 1;
    }
  | null = null;

const allImageIds = computed(() =>
  Array.from(
    new Set([...(draft.value?.imageFileIds ?? []), ...pendingImageIds.value]),
  ),
);

const draftEditable = computed(
  () =>
    draft.value !== null &&
    ["EDITING", "AI_COMPLETED", "AI_FAILED"].includes(draft.value.status),
);

const aiAnalyzing = computed(() => draft.value?.status === "AI_ANALYZING");

const AI_ANALYSIS_ERROR_MESSAGES: Record<string, string> = {
  AI_IMAGE_ANALYSIS_FAILED:
    "AI图片分析失败，请检查密钥配置或稍后重新分析。",
  KIMI_AUTH_FAILED: "Kimi 密钥无效或无权限，请检查 KIMI_API_KEY。",
  KIMI_MODEL_UNAVAILABLE: "Kimi 模型不可用，请检查 KIMI_MODEL 配置。",
  KIMI_RATE_LIMIT: "Kimi 当前繁忙或限流，请稍后重新分析。",
  KIMI_TIMEOUT: "Kimi 调用超时，请稍后重新分析。",
  KIMI_IMAGE_INVALID: "图片过大或格式不支持，请减少图片数量或重新粘贴。",
  AI_RESPONSE_INVALID: "Kimi 返回内容格式不符合要求，请重新分析。",
};

function analysisErrorMessage(errorCode: string | null | undefined): string {
  if (!errorCode) return "正文和图片已保留，可重新分析。";
  return (
    AI_ANALYSIS_ERROR_MESSAGES[errorCode] ??
    "AI图片分析失败，正文和图片已保留，可重新分析。"
  );
}

const analysisFailedTitle = computed(() => {
  if (draft.value?.status !== "AI_FAILED") return "";
  const message = analysisErrorMessage(draft.value.analysisErrorCode);
  if (message.includes("正文和图片已保留")) {
    return `AI分析失败，${message}`;
  }
  return `AI分析失败，${message}正文和图片已保留。`;
});

const chineseNumbers = ["一", "二", "三", "四", "五", "六", "七", "八"];

type InlineImageDisplaySize = {
  width: number;
  height: number;
};

const INLINE_IMAGE_ROW_GAP = 8;
const MIN_INLINE_ROW_IMAGE_WIDTH = 110;

const headingBlock = computed(
  () => draft.value?.blocks.find((block) => block.type === "HEADING") ?? null,
);

const bodyBlocks = computed(
  () => draft.value?.blocks.filter((block) => block.type !== "HEADING") ?? [],
);

const htmlReportBlock = computed(() => {
  const situation =
    draft.value?.blocks.find((block) => block.type === "SITUATION") ?? null;
  if (draft.value?.formalReportId === null || situation === null) return null;
  return looksLikeHtml(situation.content) ? situation : null;
});
const isCorrectionDraft = computed(() => draft.value?.formalReportId !== null);
const hasSubmittedAiAnalysis = computed(
  () => draft.value?.analysisSubmittedAt != null,
);

const billingPointLabel = computed(() => {
  if (draft.value === null) return "";
  return [
    draft.value.billingPointCode ?? draft.value.billingPointId,
    draft.value.billingPointName,
    draft.value.city?.name,
    draft.value.period,
  ]
    .filter((value): value is string => Boolean(value))
    .join(" ｜ ");
});

function looksLikeHtml(value: string): boolean {
  return /<\/?(div|p|table|tr|td|th|figure|img|section|article|h[1-6]|ul|ol|li)\b/i.test(
    value,
  );
}

function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function editableBlockHtml(block: DraftBlock): string {
  const content = stripBlockSectionHeading(block);
  const html = looksLikeHtml(content)
    ? content
    : escapeHtml(content).replace(/\r?\n/g, "<br>");
  const sanitized = sanitizeEditableHtml(html);
  return isCorrectionDraft.value ? sanitized : emphasizeReportCauses(sanitized);
}

function stripBlockSectionHeading(block: DraftBlock): string {
  if (block.type === "SITUATION") {
    return stripLeadingSectionHeading(block.content, "一、情况说明");
  }
  if (block.type === "ANALYSIS") {
    return stripLeadingSectionHeading(block.content, "二、排查分析");
  }
  if (block.type === "RECTIFICATION") {
    return stripLeadingSectionHeading(block.content, "三、整改小结");
  }
  return block.content;
}

function stripLeadingSectionHeading(content: string, heading: string): string {
  if (content.trim().length === 0) return content;
  const escaped = heading.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return content
    .replace(
      new RegExp(
        `^\\s*<(?:h[1-6]|p|div)\\b[^>]*>\\s*${escaped}\\s*[：:]?\\s*</(?:h[1-6]|p|div)>\\s*`,
        "i",
      ),
      "",
    )
    .replace(new RegExp(`^\\s*${escaped}\\s*[：:]?\\s*(?:<br\\s*/?>|\\r?\\n)?\\s*`, "i"), "");
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
      const leading = text.slice(start, end).match(/^\s*/)?.[0] ?? "";
      const cause = text.slice(start, end).trimStart();
      if (cause.length > 0 && !isMetricOnlyText(cause)) {
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

function sanitizeEditableHtml(html: string): string {
  const template = document.createElement("template");
  template.innerHTML = html;
  template.content
    .querySelectorAll("script,style,iframe,object,embed,link,meta")
    .forEach((element) => element.remove());
  template.content.querySelectorAll<HTMLElement>("*").forEach((element) => {
    Array.from(element.attributes).forEach((attribute) => {
      if (attribute.name.toLowerCase().startsWith("on")) {
        element.removeAttribute(attribute.name);
      }
    });
    for (const attributeName of ["src", "href"]) {
      const value = element.getAttribute(attributeName);
      if (value?.trim().toLowerCase().startsWith("javascript:")) {
        element.removeAttribute(attributeName);
      }
    }
  });
  return template.innerHTML;
}

function updateBlock(block: DraftBlock, content: string): void {
  if (draft.value === null) return;
  const target = draft.value.blocks.find((item) => item.id === block.id);
  if (target !== undefined) target.content = content;
}

function editableContentForBlock(block: DraftBlock, element: HTMLElement): string {
  if (block.type === "HEADING") {
    return element.querySelector("[data-file-id]") === null
      ? element.innerText.trim()
      : element.innerHTML.trim();
  }
  return element.innerHTML.trim();
}

function syncReportContentFromDom(): void {
  if (draft.value === null) return;
  const root = reportEditorRoot();
  if (root !== null) prepareInlineImagesForPersistence(root);
  if (htmlReportBlock.value !== null && htmlReportRef.value !== undefined) {
    updateBlock(htmlReportBlock.value, htmlReportRef.value.innerHTML.trim());
    return;
  }
  if (reportPaperRef.value === undefined) return;
  reportPaperRef.value
    .querySelectorAll<HTMLElement>("[data-block-id]")
    .forEach((element) => {
      const id = element.dataset.blockId;
      const block = draft.value?.blocks.find((item) => item.id === id);
      if (block !== undefined) {
        updateBlock(block, editableContentForBlock(block, element));
      }
    });
}

function applySavedDraftMetadata(saved: ReportDraft): void {
  if (draft.value === null || draft.value.id !== saved.id) return;
  draft.value.status = saved.status;
  draft.value.analysisStatus = saved.analysisStatus;
  draft.value.analysisTaskId = saved.analysisTaskId;
  draft.value.analysisErrorCode = saved.analysisErrorCode;
  draft.value.analysisSubmittedAt = saved.analysisSubmittedAt;
  draft.value.analysisCompletedAt = saved.analysisCompletedAt;
  draft.value.imageFileIds = saved.imageFileIds;
  draft.value.messages = saved.messages;
  draft.value.currentVersion = saved.currentVersion;
  draft.value.updatedAt = saved.updatedAt;
  draft.value.formalReportId = saved.formalReportId;
  draft.value.entityVersion = saved.entityVersion;
}

async function saveDraft(
  showSuccess = false,
  preserveEditorDom = false,
): Promise<boolean> {
  if (draft.value === null || !draftEditable.value) return false;
  if (saveInFlight !== null) return saveInFlight;
  syncReportContentFromDom();
  saving.value = true;
  const operation = (async (): Promise<boolean> => {
    try {
      if (draft.value === null) return false;
      const saved = await businessApi.drafts.save(draft.value.id, draft.value);
      if (preserveEditorDom) applySavedDraftMetadata(saved);
      else draft.value = saved;
      if (showSuccess) ElMessage.success("报告内容已保存。");
      return true;
    } catch (error) {
      await renderMissingInlineImages();
      ElMessage.error(
        error instanceof Error ? error.message : "报告内容保存失败",
      );
      return false;
    } finally {
      saving.value = false;
    }
  })();
  saveInFlight = operation;
  try {
    return await operation;
  } finally {
    saveInFlight = null;
  }
}

async function handleEditorBlur(): Promise<void> {
  await saveDraft(false, selectedInlineFigure !== null);
}

async function waitForPendingSave(): Promise<boolean> {
  return saveInFlight === null ? true : saveInFlight;
}

function stopAnalysisPolling(): void {
  if (analysisPollTimer !== null) {
    window.clearTimeout(analysisPollTimer);
    analysisPollTimer = null;
  }
}

function scheduleAnalysisPolling(delayMs = 3000): void {
  if (!aiAnalyzing.value) {
    stopAnalysisPolling();
    return;
  }
  if (analysisPollTimer !== null) return;
  analysisPollTimer = window.setTimeout(() => {
    analysisPollTimer = null;
    void pollAnalysisDraft();
  }, delayMs);
}

function syncAnalysisPolling(): void {
  if (aiAnalyzing.value) scheduleAnalysisPolling();
  else stopAnalysisPolling();
}

async function pollAnalysisDraft(): Promise<void> {
  if (draft.value === null || !aiAnalyzing.value || analysisPolling) {
    syncAnalysisPolling();
    return;
  }
  analysisPolling = true;
  const draftId = draft.value.id;
  const previousStatus = draft.value.status;
  try {
    const loaded = await businessApi.drafts.get(draftId);
    if (loaded === undefined || draft.value?.id !== draftId) return;
    const shouldRefreshEditor =
      previousStatus === "AI_ANALYZING" &&
      ["AI_COMPLETED", "AI_FAILED"].includes(loaded.status);
    await applyRemoteDraft(loaded, shouldRefreshEditor);
    if (previousStatus === "AI_ANALYZING" && loaded.status === "AI_COMPLETED") {
      ElMessage.success("AI分析完成，待人工确认。");
    } else if (
      previousStatus === "AI_ANALYZING" &&
      loaded.status === "AI_FAILED"
    ) {
      ElMessage.error(analysisErrorMessage(loaded.analysisErrorCode));
    }
  } catch {
    // Keep polling quietly; the next successful request will reconcile state.
  } finally {
    analysisPolling = false;
    syncAnalysisPolling();
  }
}

async function load(): Promise<void> {
  loading.value = true;
  errorMessage.value = "";
  try {
    const loaded = await businessApi.drafts.get(String(route.params.draftId));
    if (loaded === undefined) throw new Error("草稿不存在或无权访问");
    await applyRemoteDraft(loaded, true);
    assistantVisible.value = true;
    if (route.query.action === "image") {
      ElMessage.info("可直接在左侧报告正文任意位置粘贴图片，再点击“AI分析”。");
    }
  } catch (error) {
    errorMessage.value =
      error instanceof Error ? error.message : "草稿加载失败";
  } finally {
    loading.value = false;
  }
  syncAnalysisPolling();
}

async function applyRemoteDraft(
  loaded: ReportDraft,
  refreshEditor: boolean,
): Promise<void> {
  draft.value = loaded;
  if (refreshEditor) {
    editorRenderKey.value += 1;
    await nextTick();
  }
  await renderMissingInlineImages();
}

async function send(
  intent: "AUTO" | "ASK" | "EDIT" | "CORRECTION" | "IMAGE_ANALYSIS" = "AUTO",
  imageFileIds: string[] = [],
): Promise<boolean> {
  if (draft.value === null || sending.value) return false;
  const content = prompt.value.trim();
  if (intent !== "IMAGE_ANALYSIS" && content.length === 0) return false;
  if (intent === "IMAGE_ANALYSIS" && allImageIds.value.length === 0)
    return false;

  assistantVisible.value = true;
  assistantError.value = "";
  sending.value = true;
  if (intent === "IMAGE_ANALYSIS") {
    analyzingImages.value = true;
  } else {
    chatSending.value = true;
  }
  try {
    if (!(await waitForPendingSave())) return false;
    const loaded = await businessApi.drafts.sendMessage(
      draft.value.id,
      {
        intent,
        content: content || "分析现场图片，补充问题原因、整改建议和报告结论。",
        imageNames: imageFileIds,
        imageFileIds,
      },
      draft.value.entityVersion,
    );
    await applyRemoteDraft(loaded, intent !== "IMAGE_ANALYSIS");
    syncAnalysisPolling();
    prompt.value = "";
    if (intent !== "IMAGE_ANALYSIS") assistantIntent.value = "AUTO";
    pendingImageIds.value = pendingImageIds.value.filter(
      (id) => !imageFileIds.includes(id),
    );
    return true;
  } catch (error) {
    const message =
      error instanceof ApiProblem
        ? error.detail
        : error instanceof Error
          ? error.message
          : "AI 请求失败，草稿未修改";
    assistantError.value = message;
    ElMessage.error(message);
    return false;
  } finally {
    sending.value = false;
    chatSending.value = false;
    analyzingImages.value = false;
  }
}

async function pasteImages(event: ClipboardEvent): Promise<void> {
  const files = Array.from(event.clipboardData?.items ?? [])
    .filter((item) => item.type.startsWith("image/"))
    .map((item) => item.getAsFile())
    .filter((file): file is File => file !== null);
  if (files.length === 0) return;
  event.preventDefault();
  const editor = event.currentTarget as HTMLElement;
  rememberEditorRange();
  const pastedSizes = clipboardImageDisplaySizes(event.clipboardData);
  const markers = insertUploadMarkers(editor, files.length);
  let uploadedCount = 0;
  let lastInserted: Node | null = markers.at(-1) ?? null;
  const insertedFigures: HTMLElement[] = [];
  try {
    for (let index = 0; index < files.length; index++) {
      const marker = markers[index];
      const file = files[index];
      if (marker === undefined || file === undefined) continue;
      try {
        const [fileId] = await addImages([file]);
        if (fileId === undefined) {
          marker.remove();
          continue;
        }
        const inlineImage = createInlineImage(fileId, pastedSizes[index]);
        if (files.length > 1) {
          marker.replaceWith(inlineImage);
          lastInserted = inlineImage.closest(".inline-image-row") ?? inlineImage;
        } else {
          const targetRow = singlePasteTargetRow(marker);
          if (targetRow !== null) {
            marker.remove();
            targetRow.append(inlineImage);
            fitInlineImageRow(targetRow, editor);
            lastInserted = targetRow;
          } else {
            const spacer = createInlineImageCaretText();
            marker.replaceWith(inlineImage, spacer);
            lastInserted = spacer;
          }
        }
        await waitForInlineImageDisplaySize(inlineImage);
        insertedFigures.push(inlineImage);
        uploadedCount++;
      } catch (error) {
        marker.remove();
        ElMessage.error(
          error instanceof Error ? error.message : "粘贴图片失败",
        );
      }
    }
    if (uploadedCount > 0) {
      const row = insertedFigures[0]?.closest<HTMLElement>(".inline-image-row");
      if (row !== null && row !== undefined) fitInlineImageRow(row, editor);
      const caretTarget = connectedCaretTarget(lastInserted, insertedFigures);
      const spacer =
        caretTarget === null ? null : placePersistentCaretAfter(caretTarget);
      syncReportContentFromDom();
      if (await saveDraft(false, true)) await syncInlineImageOrder(true);
      restoreCaretAfterPaste(spacer, caretTarget, insertedFigures);
      ElMessage.success(
        `已在光标位置粘贴 ${uploadedCount} 张图片，可继续编辑文字。`,
      );
    }
  } finally {
    markers.forEach((marker) => marker.remove());
  }
}

function insertUploadMarkers(
  editor: HTMLElement,
  count: number,
): HTMLElement[] {
  const range = editorRange(editor);
  range.deleteContents();
  placeCaretAtRange(range);
  lastEditorRange = range.cloneRange();
  const markers: HTMLElement[] = [];
  const row = count > 1 ? createInlineImageRow() : null;
  for (let index = 0; index < count; index++) {
    const marker = document.createElement("span");
    marker.className = "inline-image-uploading";
    marker.contentEditable = "false";
    marker.textContent = "图片上传中...";
    if (row !== null) {
      row.append(marker);
      if (index === 0) {
        range.insertNode(row);
        range.setStartAfter(row);
      }
    } else {
      range.insertNode(marker);
      range.setStartAfter(marker);
    }
    range.collapse(true);
    markers.push(marker);
  }
  placeCaretAtRange(range);
  lastEditorRange = range.cloneRange();
  return markers;
}

function singlePasteTargetRow(marker: HTMLElement): HTMLElement | null {
  const parentRow = marker.closest<HTMLElement>(".inline-image-row");
  if (parentRow !== null) return parentRow;
  const previous = previousMeaningfulSibling(marker);
  if (previous instanceof HTMLElement && previous.classList.contains("inline-image-row")) {
    return previous;
  }
  const range = lastEditorRange;
  if (range !== null) {
    const container =
      range.startContainer instanceof HTMLElement
        ? range.startContainer
        : range.startContainer.parentElement;
    const row = container?.closest<HTMLElement>(".inline-image-row");
    if (row !== null && row !== undefined) return row;
  }
  return null;
}

function previousMeaningfulSibling(node: Node): Node | null {
  let previous = node.previousSibling;
  while (
    previous !== null &&
    previous.nodeType === Node.TEXT_NODE &&
    (previous.textContent ?? "").trim() === ""
  ) {
    previous = previous.previousSibling;
  }
  return previous;
}

function editorRange(editor: HTMLElement): Range {
  const selection = window.getSelection();
  if (
    selection !== null &&
    selection.rangeCount > 0 &&
    editor.contains(selection.getRangeAt(0).commonAncestorContainer)
  ) {
    return selection.getRangeAt(0).cloneRange();
  }
  if (
    lastEditorRange !== null &&
    editor.contains(lastEditorRange.commonAncestorContainer)
  ) {
    return lastEditorRange.cloneRange();
  }
  const range = document.createRange();
  range.selectNodeContents(editor);
  range.collapse(false);
  return range;
}

function rememberEditorRange(): void {
  const root = reportEditorRoot();
  const selection = window.getSelection();
  if (root === null || selection === null || selection.rangeCount === 0) {
    return;
  }
  const range = selection.getRangeAt(0);
  if (root.contains(range.commonAncestorContainer)) {
    lastEditorRange = range.cloneRange();
  }
}

function placeCaretAtRange(range: Range): void {
  const selection = window.getSelection();
  if (selection === null) return;
  selection.removeAllRanges();
  selection.addRange(range);
  lastEditorRange = range.cloneRange();
}

function createInlineImage(
  fileId: string,
  displaySize?: InlineImageDisplaySize,
): HTMLElement {
  const figure = document.createElement("figure");
  figure.className = "inline-report-image";
  figure.dataset.fileId = fileId;
  figure.contentEditable = "false";
  figure.draggable = false;
  const image = document.createElement("img");
  image.src = imageUrl(fileId);
  image.alt = "稽核证据图片";
  image.dataset.fileId = fileId;
  image.draggable = false;
  attachImageFallback(image);
  figure.append(image);
  if (displaySize !== undefined) {
    applyInlineImageDisplaySize(figure, image, displaySize);
  }
  image.addEventListener(
    "load",
    () => {
      captureInlineImageDisplaySize(figure, image);
    },
    { once: true },
  );
  return figure;
}

function createInlineImageRow(): HTMLElement {
  const row = document.createElement("div");
  row.className = "inline-image-row";
  row.dataset.imageGroupId =
    globalThis.crypto?.randomUUID?.() ??
    `image-group-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return row;
}

function clipboardImageDisplaySizes(
  data: DataTransfer | null,
): Array<InlineImageDisplaySize | undefined> {
  const html = data?.getData("text/html") ?? "";
  if (!html) return [];
  const template = document.createElement("template");
  template.innerHTML = html;
  return Array.from(template.content.querySelectorAll<HTMLImageElement>("img"))
    .map(imageDisplaySizeFromElement);
}

function imageDisplaySizeFromElement(
  image: HTMLImageElement,
): InlineImageDisplaySize | undefined {
  const attrWidth = parseCssPixelValue(image.getAttribute("width"));
  const attrHeight = parseCssPixelValue(image.getAttribute("height"));
  const styleWidth = parseCssPixelValue(image.style.width);
  const styleHeight = parseCssPixelValue(image.style.height);
  const width = styleWidth ?? attrWidth;
  const height = styleHeight ?? attrHeight;
  if (width !== undefined && height !== undefined) {
    return { width, height };
  }
  if (width !== undefined || height !== undefined) {
    return undefined;
  }
  return undefined;
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

function applyInlineImageDisplaySize(
  figure: HTMLElement,
  image: HTMLImageElement,
  size: InlineImageDisplaySize,
): void {
  const width = Math.round(size.width * 100) / 100;
  const height = Math.round(imageDisplayHeightForWidth(image, width, size.height) * 100) / 100;
  if (width <= 0 || height <= 0) return;
  figure.dataset.displayWidth = String(width);
  figure.dataset.displayHeight = String(height);
  image.dataset.displayWidth = String(width);
  image.dataset.displayHeight = String(height);
  image.setAttribute("width", String(Math.round(width)));
  image.setAttribute("height", String(Math.round(height)));
  image.style.width = `${width}px`;
  image.style.height = `${height}px`;
}

function imageDisplayHeightForWidth(
  image: HTMLImageElement,
  width: number,
  fallbackHeight: number,
): number {
  if (image.naturalWidth > 0 && image.naturalHeight > 0) {
    return (width * image.naturalHeight) / image.naturalWidth;
  }
  return fallbackHeight;
}

function captureInlineImageDisplaySize(
  figure: HTMLElement,
  image: HTMLImageElement,
): void {
  const rect = image.getBoundingClientRect();
  if (rect.width > 0 && (rect.height > 0 || image.naturalHeight > 0)) {
    applyInlineImageDisplaySize(figure, image, {
      width: rect.width,
      height: rect.height > 0 ? rect.height : image.naturalHeight,
    });
  }
}

function waitForInlineImageDisplaySize(figure: HTMLElement): Promise<void> {
  const image = figure.querySelector<HTMLImageElement>("img");
  if (image === null) return Promise.resolve();
  if (image.complete && image.naturalWidth > 0) {
    captureInlineImageDisplaySize(figure, image);
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    const finish = (): void => {
      window.clearTimeout(timer);
      image.removeEventListener("load", onLoad);
      image.removeEventListener("error", onError);
      if (image.naturalWidth > 0) captureInlineImageDisplaySize(figure, image);
      resolve();
    };
    const onLoad = (): void => finish();
    const onError = (): void => finish();
    const timer = window.setTimeout(finish, 2000);
    image.addEventListener("load", onLoad, { once: true });
    image.addEventListener("error", onError, { once: true });
  });
}

function fitInlineImageRow(row: HTMLElement, editor: HTMLElement): void {
  const figures = Array.from(
    row.querySelectorAll<HTMLElement>("figure[data-file-id]"),
  );
  if (figures.length < 2) return;
  const availableWidth = inlineImageRowAvailableWidth(row, editor);
  if (availableWidth <= MIN_INLINE_ROW_IMAGE_WIDTH) return;
  const sizes = figures
    .map((figure) => {
      const image = figure.querySelector<HTMLImageElement>("img");
      if (image === null) return null;
      return { figure, image, size: currentInlineImageSize(figure, image) };
    })
    .filter(
      (
        value,
      ): value is {
        figure: HTMLElement;
        image: HTMLImageElement;
        size: InlineImageDisplaySize;
      } => value !== null,
    );
  if (sizes.length < 2) return;
  const totalGap = INLINE_IMAGE_ROW_GAP * (sizes.length - 1);
  const imageWidthBudget = Math.max(MIN_INLINE_ROW_IMAGE_WIDTH, availableWidth - totalGap);
  const currentTotalWidth = sizes.reduce((sum, item) => sum + item.size.width, 0);
  if (currentTotalWidth <= imageWidthBudget) return;
  const scale = imageWidthBudget / currentTotalWidth;
  sizes.forEach(({ figure, image, size }) => {
    const targetWidth = Math.max(
      MIN_INLINE_ROW_IMAGE_WIDTH,
      Math.floor(size.width * scale),
    );
    applyInlineImageDisplaySize(figure, image, {
      width: targetWidth,
      height: size.height,
    });
  });
}

function inlineImageRowAvailableWidth(row: HTMLElement, editor: HTMLElement): number {
  const rowWidth = row.getBoundingClientRect().width;
  if (rowWidth > 0) return rowWidth;
  return Math.max(0, editor.clientWidth - 32);
}

function currentInlineImageSize(
  figure: HTMLElement,
  image: HTMLImageElement,
): InlineImageDisplaySize {
  const rect = image.getBoundingClientRect();
  const width =
    firstPositiveNumber(
      parseCssPixelValue(figure.dataset.displayWidth ?? null),
      parseCssPixelValue(image.dataset.displayWidth ?? null),
      parseCssPixelValue(image.getAttribute("width")),
      parseCssPixelValue(image.style.width),
      rect.width,
      image.naturalWidth,
    ) ??
    MIN_INLINE_ROW_IMAGE_WIDTH;
  const height =
    firstPositiveNumber(
      parseCssPixelValue(figure.dataset.displayHeight ?? null),
      parseCssPixelValue(image.dataset.displayHeight ?? null),
      parseCssPixelValue(image.getAttribute("height")),
      parseCssPixelValue(image.style.height),
      rect.height,
      image.naturalHeight,
    ) ??
    width;
  return {
    width: Math.max(1, width),
    height: Math.max(1, height),
  };
}

function firstPositiveNumber(...values: Array<number | undefined>): number | undefined {
  return values.find((value) => value !== undefined && Number.isFinite(value) && value > 0);
}

function createInlineImageCaretText(): Text {
  return document.createTextNode(" ");
}

function placeCaretAfter(node: Node): void {
  const selection = window.getSelection();
  if (selection === null) return;
  const range = document.createRange();
  range.setStartAfter(node);
  range.collapse(true);
  selection.removeAllRanges();
  selection.addRange(range);
  lastEditorRange = range.cloneRange();
}

function placePersistentCaretAfter(node: Node): Text {
  const spacer = createInlineImageCaretText();
  node.parentNode?.insertBefore(spacer, node.nextSibling);
  placeCaretAfter(spacer);
  return spacer;
}

function connectedCaretTarget(
  preferred: Node | null,
  insertedFigures: HTMLElement[],
): Node | null {
  if (preferred !== null && preferred.isConnected) return preferred;
  for (let index = insertedFigures.length - 1; index >= 0; index--) {
    const figure = insertedFigures[index];
    if (figure?.isConnected) {
      return figure.closest(".inline-image-row") ?? figure;
    }
  }
  return null;
}

function restoreCaretAfterPaste(
  spacer: Text | null,
  target: Node | null,
  insertedFigures: HTMLElement[],
): void {
  if (spacer !== null && spacer.isConnected) {
    placeCaretAfter(spacer);
    return;
  }
  const fallback = connectedCaretTarget(target, insertedFigures);
  if (fallback !== null) {
    placePersistentCaretAfter(fallback);
  }
}

async function addImages(files: File[]): Promise<string[]> {
  if (draft.value === null) throw new Error("草稿尚未加载完成");
  if (uploadingImages.value || imageUpdating.value || sending.value) {
    throw new Error("当前操作尚未完成，请稍后再添加图片");
  }
  const accepted = files.filter((file) =>
    ["image/png", "image/jpeg"].includes(file.type),
  );
  if (accepted.length !== files.length)
    throw new Error("仅支持 PNG 或 JPEG 图片");
  if (accepted.some((file) => file.size > 10 * 1024 * 1024))
    throw new Error("单张图片不能超过 10 MiB");
  uploadingImages.value = true;
  try {
    if (!(await waitForPendingSave())) return [];
    const uploadedIds: string[] = [];
    for (const file of accepted) {
      const result = await businessApi.drafts.uploadImage(draft.value.id, file);
      draft.value.entityVersion = result.entityVersion;
      if (!draft.value.imageFileIds.includes(result.fileId)) {
        draft.value.imageFileIds.push(result.fileId);
      }
      if (!pendingImageIds.value.includes(result.fileId)) {
        pendingImageIds.value.push(result.fileId);
      }
      uploadedIds.push(result.fileId);
    }
    return uploadedIds;
  } finally {
    uploadingImages.value = false;
  }
}

async function analyzeAllImages(): Promise<void> {
  if (uploadingImages.value || imageUpdating.value) {
    ElMessage.info("图片正在添加或调整，请稍后再分析。");
    return;
  }
  if (sending.value) return;
  if (draft.value === null) return;
  groupVisualInlineImageRowsForAnalysis();
  syncReportContentFromDom();
  let imageIds = inlineImageIdsFromDom();
  if (imageIds.length === 0) {
    ElMessage.warning("请先在左侧报告正文中粘贴图片。");
    return;
  }
  if (aiAnalyzing.value) {
    ElMessage.info("AI正在后台分析，请稍后再提交。");
    return;
  }
  const saved = await saveDraft(false);
  if (!saved) {
    assistantError.value = "当前报告尚未保存，已停止图片分析。";
    return;
  }
  await syncInlineImageOrder();
  imageIds = inlineImageIdsFromDom();
  if (imageIds.length === 0) {
    ElMessage.warning("请先在左侧报告正文中粘贴图片。");
    return;
  }
  const previousPrompt = prompt.value;
  prompt.value =
    "按真实电费稽核说明短格式生成。排查分析必须先按我粘贴图片的原始顺序保留全部图片；仅设备图、机房图在图片正上方补充一行简短说明，设备情况要尽量写清运营商、制式、厂家/型号、设备类型和数量，不能识别的内容直接不写，不要写待核实；其他系统截图、缴费截图、标杆截图、位置点截图不加说明；本期超标原因分析必须放在排查分析最后一段、全部图片之后、整改小结之前；优先结合同报账点历史报告里的明确原因判断，不要默认写待核实；整改小结跟随本期原因变化，不固定套话；正文只写业务判断，不写同点历史、本市经验、外市参考、证据来源等内部话术；文字简洁正式，输出前检查错别字。";
  const succeeded = await send("IMAGE_ANALYSIS", imageIds);
  if (succeeded) {
    ElMessage.success(
      "AI分析任务已提交，可留在当前页等待，也可返回列表继续处理其他报账点。",
    );
  } else {
    prompt.value = previousPrompt;
  }
}

function imageUrl(id: string): string {
  const draftId = draft.value?.id;
  if (draftId === undefined) return `/api/v1/files/${encodeURIComponent(id)}?inline=true`;
  return `/api/v1/report-drafts/${encodeURIComponent(draftId)}/images/${encodeURIComponent(id)}/content?inline=true`;
}

function attachImageFallback(image: HTMLImageElement): void {
  image.onerror = () => {
    const fileId = image.dataset.fileId;
    if (fileId === undefined || image.dataset.usedGenericFallback === "true") return;
    image.dataset.usedGenericFallback = "true";
    image.src = `/api/v1/files/${encodeURIComponent(fileId)}?inline=true`;
  };
}

function prepareInlineImagesForPersistence(root: HTMLElement): void {
  removeInlineImageEditorChrome(root);
  normalizeInlineImageRows(root);
  root
    .querySelectorAll<HTMLImageElement>("figure[data-file-id] img[data-used-generic-fallback]")
    .forEach((image) => delete image.dataset.usedGenericFallback);
  root
    .querySelectorAll<HTMLElement>("figure[data-file-id]")
    .forEach(clearInlineImageFloatingStyles);
  normalizeInlineImageRows(root);
  syncInlineImageDisplaySizes(root);
}

function removeInlineImageEditorChrome(root: HTMLElement): void {
  root
    .querySelectorAll<HTMLElement>(
      ".inline-image-resize-handle, .inline-image-drop-marker",
    )
    .forEach((element) => element.remove());
  root
    .querySelectorAll<HTMLElement>(".inline-report-image.is-selected")
    .forEach((figure) => figure.classList.remove("is-selected"));
}

function syncInlineImageDisplaySizes(root: HTMLElement): void {
  root.querySelectorAll<HTMLElement>("figure[data-file-id]").forEach((figure) => {
    const image = figure.querySelector<HTMLImageElement>("img");
    if (image === null) return;
    const explicitWidth =
      parseCssPixelValue(figure.dataset.displayWidth ?? null) ??
      parseCssPixelValue(image.dataset.displayWidth ?? null) ??
      parseCssPixelValue(image.getAttribute("width")) ??
      parseCssPixelValue(image.style.width);
    const explicitHeight =
      parseCssPixelValue(figure.dataset.displayHeight ?? null) ??
      parseCssPixelValue(image.dataset.displayHeight ?? null) ??
      parseCssPixelValue(image.getAttribute("height")) ??
      parseCssPixelValue(image.style.height);
    if (explicitWidth !== undefined && explicitHeight !== undefined) {
      applyInlineImageDisplaySize(figure, image, {
        width: explicitWidth,
        height: explicitHeight,
      });
      return;
    }
    captureInlineImageDisplaySize(figure, image);
  });
}

function normalizeInlineImageElements(root: HTMLElement): void {
  normalizeInlineImageRows(root);
  root.querySelectorAll<HTMLImageElement>("img[data-file-id]").forEach((image) => {
    const fileId = image.dataset.fileId;
    if (fileId === undefined || fileId.length === 0) return;
    image.src = imageUrl(fileId);
    image.alt ||= "稽核证据图片";
    image.draggable = false;
    attachImageFallback(image);
    let figure = image.closest("figure");
    if (!(figure instanceof HTMLElement)) {
      figure = document.createElement("figure");
      image.replaceWith(figure);
      figure.append(image);
    }
    figure.dataset.fileId = fileId;
    figure.classList.add("inline-report-image");
    clearInlineImageFloatingStyles(figure);
    figure.classList.remove("is-selected");
    figure.contentEditable = "false";
    figure.draggable = false;
    applyExistingInlineImageDisplaySize(figure, image);
  });

  root.querySelectorAll<HTMLElement>("figure[data-file-id]").forEach((figure) => {
    const fileId = figure.dataset.fileId;
    if (fileId === undefined || fileId.length === 0) return;
    figure.classList.add("inline-report-image");
    clearInlineImageFloatingStyles(figure);
    figure.classList.remove("is-selected");
    figure.contentEditable = "false";
    figure.draggable = false;
    let image = figure.querySelector<HTMLImageElement>("img");
    if (image === null) {
      image = document.createElement("img");
      figure.append(image);
    }
    image.src = imageUrl(fileId);
    image.alt ||= "稽核证据图片";
    image.dataset.fileId = fileId;
    image.draggable = false;
    delete image.dataset.usedGenericFallback;
    attachImageFallback(image);
    applyExistingInlineImageDisplaySize(figure, image);
  });
  normalizeInlineImageRows(root);
  removeLegacyInlineImageSpacers(root);
}

function normalizeInlineImageRows(root: HTMLElement): void {
  if (root.classList.contains("inline-image-row")) {
    normalizeInlineImageRow(root);
    return;
  }
  root
    .querySelectorAll<HTMLElement>(".inline-image-row")
    .forEach(normalizeInlineImageRow);
}

function normalizeInlineImageRow(row: HTMLElement): void {
  if (row.dataset.imageGroupId === undefined || row.dataset.imageGroupId === "") {
    row.dataset.imageGroupId =
      globalThis.crypto?.randomUUID?.() ??
      `image-group-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  }
  row.style.removeProperty("position");
  row.style.removeProperty("left");
  row.style.removeProperty("top");
  row.style.removeProperty("right");
  row.style.removeProperty("bottom");
  row.style.removeProperty("z-index");
  row.style.removeProperty("transform");
  row.querySelectorAll<HTMLElement>("figure[data-file-id]").forEach((figure) => {
    figure.classList.add("inline-report-image");
    clearInlineImageFloatingStyles(figure);
  });
  const figures = Array.from(row.querySelectorAll<HTMLElement>("figure[data-file-id]"));
  if (figures.length === 0) {
    row.remove();
    return;
  }
  if (figures.length === 1) {
    const [figure] = figures;
    if (figure !== undefined) row.replaceWith(figure);
  }
}

function groupVisualInlineImageRowsForAnalysis(): void {
  const root = reportEditorRoot();
  if (root === null) return;
  unwrapInlineImageWrappers(root);
  const containers = [
    root,
    ...Array.from(root.querySelectorAll<HTMLElement>("p, div, section, article")),
  ].filter((element) => !element.classList.contains("inline-image-row"));
  containers.forEach((container) => {
    let run: HTMLElement[] = [];
    const flush = (): void => {
      wrapVisualFigureRuns(run);
      run = [];
    };
    Array.from(container.childNodes).forEach((node) => {
      if (
        node instanceof HTMLElement &&
        node.matches("figure[data-file-id]") &&
        node.closest(".inline-image-row") === null
      ) {
        run.push(node);
        return;
      }
      if (node.nodeType === Node.TEXT_NODE && (node.textContent ?? "").trim() === "") {
        return;
      }
      flush();
    });
    flush();
  });
  normalizeInlineImageRows(root);
}

function unwrapInlineImageWrappers(root: HTMLElement): void {
  root.querySelectorAll<HTMLElement>(".word-inline-image").forEach((wrapper) => {
    const figure = wrapper.querySelector<HTMLElement>("figure[data-file-id]");
    if (figure === null) return;
    wrapper.replaceWith(figure);
  });
}

function wrapVisualFigureRuns(figures: HTMLElement[]): void {
  if (figures.length < 2) return;
  let group: HTMLElement[] = [];
  const flush = (): void => {
    if (group.length >= 2) wrapFiguresInInlineImageRow(group);
    group = [];
  };
  figures.forEach((figure) => {
    if (group.length === 0 || figuresShareVisualRow(group[0], figure)) {
      group.push(figure);
      return;
    }
    flush();
    group.push(figure);
  });
  flush();
}

function figuresShareVisualRow(first: HTMLElement | undefined, next: HTMLElement): boolean {
  if (first === undefined) return false;
  const firstRect = first.getBoundingClientRect();
  const nextRect = next.getBoundingClientRect();
  if (firstRect.width <= 0 || nextRect.width <= 0) return false;
  const verticalOverlap =
    Math.min(firstRect.bottom, nextRect.bottom) - Math.max(firstRect.top, nextRect.top);
  const minHeight = Math.min(firstRect.height, nextRect.height);
  return verticalOverlap >= Math.max(12, minHeight * 0.35);
}

function wrapFiguresInInlineImageRow(figures: HTMLElement[]): void {
  const [firstFigure] = figures;
  if (firstFigure === undefined || firstFigure.parentNode === null) return;
  const row = createInlineImageRow();
  firstFigure.before(row);
  figures.forEach((figure) => row.append(figure));
  const editor = reportEditorRoot();
  if (editor !== null) fitInlineImageRow(row, editor);
}

function clearInlineImageFloatingStyles(figure: HTMLElement): void {
  figure.classList.remove("is-floating", "is-dragging");
  figure.style.removeProperty("position");
  figure.style.removeProperty("left");
  figure.style.removeProperty("top");
  figure.style.removeProperty("right");
  figure.style.removeProperty("bottom");
  figure.style.removeProperty("width");
  figure.style.removeProperty("height");
  figure.style.removeProperty("margin");
  figure.style.removeProperty("z-index");
  figure.style.removeProperty("transform");
  figure.style.removeProperty("pointer-events");
}

function applyExistingInlineImageDisplaySize(
  figure: HTMLElement,
  image: HTMLImageElement,
): void {
  const width =
    parseCssPixelValue(figure.dataset.displayWidth ?? null) ??
    parseCssPixelValue(image.dataset.displayWidth ?? null) ??
    parseCssPixelValue(image.getAttribute("width")) ??
    parseCssPixelValue(image.style.width);
  const height =
    parseCssPixelValue(figure.dataset.displayHeight ?? null) ??
    parseCssPixelValue(image.dataset.displayHeight ?? null) ??
    parseCssPixelValue(image.getAttribute("height")) ??
    parseCssPixelValue(image.style.height);
  if (width !== undefined && height !== undefined) {
    applyInlineImageDisplaySize(figure, image, { width, height });
  }
}

function removeLegacyInlineImageSpacers(root: HTMLElement): void {
  root
    .querySelectorAll<HTMLBRElement>('br[data-inline-image-spacer="true"]')
    .forEach((spacer) => spacer.remove());
}

function defaultInlineImageContainer(): HTMLElement | null {
  if (htmlReportBlock.value !== null) return htmlReportRef.value ?? null;
  if (reportPaperRef.value === undefined) return null;
  const editableBlocks = Array.from(
    reportPaperRef.value.querySelectorAll<HTMLElement>(
      ".editable-report-block[data-block-id]",
    ),
  );
  return editableBlocks.at(-1) ?? reportPaperRef.value;
}

function reportEditorRoot(): HTMLElement | null {
  return htmlReportRef.value ?? reportPaperRef.value ?? null;
}

function inlineImageIdsFromDom(): string[] {
  const root = reportEditorRoot();
  if (root === null) return [];
  return Array.from(
    new Set(
      Array.from(
        root.querySelectorAll<HTMLElement>(
          "figure[data-file-id], img[data-file-id]",
        ),
      )
        .map((element) => element.dataset.fileId)
        .filter((value): value is string => value !== undefined),
    ),
  );
}

async function renderMissingInlineImages(): Promise<void> {
  await nextTick();
  const defaultContainer = defaultInlineImageContainer();
  const root = reportEditorRoot();
  if (defaultContainer === null || root === null) return;
  root.querySelectorAll("figcaption").forEach((caption) => caption.remove());
  normalizeInlineImageElements(root);
  const unassignedImages = Array.from(root.querySelectorAll("img")).filter(
    (image) =>
      image.dataset.fileId === undefined &&
      image.closest<HTMLElement>("[data-file-id]")?.dataset.fileId ===
        undefined,
  );
  for (const fileId of allImageIds.value) {
    const alreadyRendered = Array.from(
      root.querySelectorAll<HTMLElement>("[data-file-id]"),
    ).some((element) => element.dataset.fileId === fileId);
    if (alreadyRendered) continue;
    const importedImage = unassignedImages.shift();
    if (importedImage !== undefined) {
      importedImage.dataset.fileId = fileId;
      let figure = importedImage.closest("figure");
      if (!(figure instanceof HTMLElement)) {
        figure = document.createElement("figure");
        importedImage.replaceWith(figure);
        figure.append(importedImage);
      }
      figure.dataset.fileId = fileId;
      figure.classList.add("inline-report-image");
      clearInlineImageFloatingStyles(figure);
      figure.classList.remove("is-selected");
      figure.contentEditable = "false";
      figure.draggable = false;
      importedImage.src = imageUrl(fileId);
      importedImage.draggable = false;
      continue;
    }
  }
  normalizeInlineImageElements(root);
}

function selectInlineImage(event: MouseEvent): void {
  const target = event.target;
  if (
    target instanceof Element &&
    target.closest(".inline-image-resize-handle") !== null
  ) {
    return;
  }
  const figure =
    target instanceof Element
      ? target.closest<HTMLElement>("figure[data-file-id]")
      : null;
  if (figure === null) {
    selectedInlineFigure?.classList.remove("is-selected");
    selectedInlineFigure = null;
    rememberEditorRange();
    return;
  }
  selectInlineFigure(figure);
  window.getSelection()?.removeAllRanges();
}

function selectInlineFigure(figure: HTMLElement): void {
  if (selectedInlineFigure !== figure) {
    selectedInlineFigure?.classList.remove("is-selected");
    selectedInlineFigure
      ?.querySelectorAll(".inline-image-resize-handle")
      .forEach((handle) => handle.remove());
  }
  selectedInlineFigure = figure;
  figure.classList.add("is-selected");
  ensureResizeHandles(figure);
}

function ensureResizeHandles(figure: HTMLElement): void {
  if (figure.querySelector(".inline-image-resize-handle") !== null) return;
  ["nw", "ne", "se", "sw"].forEach((corner) => {
    const handle = document.createElement("span");
    handle.className = `inline-image-resize-handle ${corner}`;
    handle.dataset.resizeHandle = corner;
    handle.contentEditable = "false";
    figure.append(handle);
  });
}

function rememberTextSelection(event: Event): void {
  const target = event.target;
  if (target instanceof Element && target.closest("figure[data-file-id]") !== null) {
    return;
  }
  rememberEditorRange();
}

function selectedInlineImageElement(): HTMLElement | null {
  const root = reportEditorRoot();
  if (
    selectedInlineFigure !== null &&
    selectedInlineFigure.isConnected &&
    root?.contains(selectedInlineFigure)
  ) {
    return selectedInlineFigure;
  }
  const selection = window.getSelection();
  if (selection === null || selection.rangeCount === 0) return null;
  const range = selection.getRangeAt(0);
  const candidate =
    range.startContainer instanceof Element
      ? range.startContainer.closest<HTMLElement>("figure[data-file-id]")
      : range.startContainer.parentElement?.closest<HTMLElement>(
          "figure[data-file-id]",
        );
  if (candidate !== undefined && candidate !== null && root?.contains(candidate)) {
    return candidate;
  }
  const fragment = range.cloneContents();
  const fileId =
    fragment.querySelector<HTMLElement>("figure[data-file-id]")?.dataset
      .fileId ?? null;
  if (fileId === null) return null;
  return (
    root?.querySelector<HTMLElement>(
      `figure[data-file-id="${CSS.escape(fileId)}"]`,
    ) ?? null
  );
}

function handleEditorKeydown(event: KeyboardEvent): void {
  if (event.key !== "Backspace" && event.key !== "Delete") return;
  const figure = selectedInlineImageElement();
  if (figure === null) return;
  event.preventDefault();
  void removeInlineImageFromReport(figure);
}

async function removeInlineImageFromReport(figure: HTMLElement): Promise<boolean> {
  if (draft.value === null) return false;
  const fileId = figure.dataset.fileId;
  if (fileId === undefined || fileId.length === 0) return false;
  figure
    .querySelectorAll(".inline-image-resize-handle")
    .forEach((handle) => handle.remove());
  selectedInlineFigure = null;
  const row = figure.closest<HTMLElement>(".inline-image-row");
  figure.remove();
  if (row !== null) normalizeInlineImageRows(row);
  syncReportContentFromDom();
  if (!(await saveDraft(false, true))) return false;
  const saved = await businessApi.drafts.removeImage(
    draft.value.id,
    fileId,
    draft.value.entityVersion,
  );
  applySavedDraftMetadata(saved);
  pendingImageIds.value = pendingImageIds.value.filter(
    (pendingId) => pendingId !== fileId,
  );
  await syncInlineImageOrder(true);
  return true;
}

function startInlineImagePointer(event: PointerEvent): void {
  if (!draftEditable.value || event.button !== 0) return;
  const target = event.target;
  if (!(target instanceof Element)) return;
  const figure = target.closest<HTMLElement>("figure[data-file-id]");
  if (figure === null) return;
  const editor = event.currentTarget;
  if (!(editor instanceof HTMLElement) || !editor.contains(figure)) return;
  event.preventDefault();
  event.stopPropagation();
  selectInlineFigure(figure);
  window.getSelection()?.removeAllRanges();

  const handle = target.closest<HTMLElement>(".inline-image-resize-handle");
  if (handle !== null) {
    startInlineImageResize(event, figure, handle);
    return;
  }

  figure.setPointerCapture(event.pointerId);
  inlineImageInteraction = {
    mode: "pending",
    figure,
    editor,
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
  };
}

function startInlineImageResize(
  event: PointerEvent,
  figure: HTMLElement,
  handle: HTMLElement,
): void {
  const image = figure.querySelector<HTMLImageElement>("img");
  if (image === null) return;
  captureInlineImageDisplaySize(figure, image);
  const rect = image.getBoundingClientRect();
  const width = Math.max(1, rect.width);
  const height = Math.max(1, rect.height);
  figure.setPointerCapture(event.pointerId);
  figure.classList.add("is-resizing");
  inlineImageInteraction = {
    mode: "resizing",
    figure,
    pointerId: event.pointerId,
    startX: event.clientX,
    startWidth: width,
    aspectRatio: width / height,
    direction: handle.dataset.resizeHandle?.includes("w") ? -1 : 1,
  };
}

function moveInlineImagePointer(event: PointerEvent): void {
  const state = inlineImageInteraction;
  if (state === null || state.pointerId !== event.pointerId) return;
  event.preventDefault();
  if (state.mode === "resizing") {
    resizeInlineImage(state, event.clientX);
    return;
  }
  if (state.mode === "pending") {
    const moved = Math.hypot(
      event.clientX - state.startX,
      event.clientY - state.startY,
    );
    if (moved < 5) return;
    const marker = createInlineImageDropMarker(state.figure);
    state.figure.after(marker);
    state.figure.classList.add("is-dragging");
    inlineImageInteraction = {
      mode: "dragging",
      figure: state.figure,
      editor: state.editor,
      pointerId: state.pointerId,
      marker,
    };
    moveDropMarker(state.editor, state.figure, marker, event.clientX, event.clientY);
    return;
  }
  moveDropMarker(state.editor, state.figure, state.marker, event.clientX, event.clientY);
}

async function finishInlineImagePointer(event: PointerEvent): Promise<void> {
  const state = inlineImageInteraction;
  if (state === null || state.pointerId !== event.pointerId) return;
  inlineImageInteraction = null;
  state.figure.releasePointerCapture(event.pointerId);
  if (state.mode === "pending") return;
  if (state.mode === "dragging") {
    state.figure.classList.remove("is-dragging");
    state.marker.replaceWith(state.figure);
    const row = state.figure.closest<HTMLElement>(".inline-image-row");
    if (row !== null) {
      normalizeInlineImageRows(row);
      fitInlineImageRow(row, state.editor);
    }
    selectInlineFigure(state.figure);
    placeCaretAfterInlineImage(state.figure);
    syncReportContentFromDom();
    if (await saveDraft(false, true)) await syncInlineImageOrder(true);
    return;
  }
  state.figure.classList.remove("is-resizing");
  selectInlineFigure(state.figure);
  syncReportContentFromDom();
  await saveDraft(false, true);
}

function cancelInlineImagePointer(event: PointerEvent): void {
  const state = inlineImageInteraction;
  if (state === null || state.pointerId !== event.pointerId) return;
  inlineImageInteraction = null;
  state.figure.releasePointerCapture(event.pointerId);
  state.figure.classList.remove("is-dragging", "is-resizing");
  if (state.mode === "dragging") state.marker.remove();
}

function resizeInlineImage(
  state: Extract<typeof inlineImageInteraction, { mode: "resizing" }>,
  clientX: number,
): void {
  const image = state.figure.querySelector<HTMLImageElement>("img");
  if (image === null) return;
  const editor = state.figure.closest<HTMLElement>(".report-paper");
  const maxWidth = Math.max(80, (editor?.clientWidth ?? 720) - 48);
  const delta = (clientX - state.startX) * state.direction;
  const width = Math.min(maxWidth, Math.max(80, state.startWidth + delta));
  const height = width / state.aspectRatio;
  applyInlineImageDisplaySize(state.figure, image, { width, height });
}

function createInlineImageDropMarker(figure: HTMLElement): HTMLElement {
  const marker = document.createElement("span");
  marker.className = "inline-image-drop-marker";
  marker.contentEditable = "false";
  const rect = figure.getBoundingClientRect();
  marker.style.width = `${Math.max(80, rect.width)}px`;
  marker.style.height = `${Math.max(36, rect.height)}px`;
  return marker;
}

function moveDropMarker(
  editor: HTMLElement,
  draggedFigure: HTMLElement,
  marker: HTMLElement,
  clientX: number,
  clientY: number,
): void {
  const range = editorRangeFromPoint(editor, draggedFigure, marker, clientX, clientY);
  if (range === null) return;
  marker.remove();
  range.deleteContents();
  range.insertNode(marker);
}

function editorRangeFromPoint(
  editor: HTMLElement,
  draggedFigure: HTMLElement,
  marker: HTMLElement,
  clientX: number,
  clientY: number,
): Range | null {
  draggedFigure.style.pointerEvents = "none";
  marker.style.pointerEvents = "none";
  const caretRange = caretRangeFromPoint(clientX, clientY);
  draggedFigure.style.removeProperty("pointer-events");
  marker.style.removeProperty("pointer-events");
  if (
    caretRange !== null &&
    editor.contains(caretRange.commonAncestorContainer) &&
    !draggedFigure.contains(caretRange.commonAncestorContainer) &&
    !marker.contains(caretRange.commonAncestorContainer)
  ) {
    return caretRange;
  }
  return nearestImageBoundaryRange(editor, draggedFigure, marker, clientY);
}

function caretRangeFromPoint(clientX: number, clientY: number): Range | null {
  const documentWithCaret = document as Document & {
    caretRangeFromPoint?: (x: number, y: number) => Range | null;
    caretPositionFromPoint?: (
      x: number,
      y: number,
    ) => { offsetNode: Node; offset: number } | null;
  };
  const range = documentWithCaret.caretRangeFromPoint?.(clientX, clientY);
  if (range !== undefined) return range;
  const position = documentWithCaret.caretPositionFromPoint?.(clientX, clientY);
  if (position === undefined || position === null) return null;
  const fallback = document.createRange();
  fallback.setStart(position.offsetNode, position.offset);
  fallback.collapse(true);
  return fallback;
}

function nearestImageBoundaryRange(
  editor: HTMLElement,
  draggedFigure: HTMLElement,
  marker: HTMLElement,
  clientY: number,
): Range | null {
  const candidates = Array.from(
    editor.querySelectorAll<HTMLElement>(
      "p, div, h1, h2, h3, figure[data-file-id]",
    ),
  ).filter((element) => element !== draggedFigure && element !== marker);
  let closest: { element: HTMLElement; before: boolean; distance: number } | null = null;
  for (const element of candidates) {
    const rect = element.getBoundingClientRect();
    if (rect.height <= 0) continue;
    const before = clientY < rect.top + rect.height / 2;
    const edge = before ? rect.top : rect.bottom;
    const distance = Math.abs(clientY - edge);
    if (closest === null || distance < closest.distance) {
      closest = { element, before, distance };
    }
  }
  if (closest === null) {
    const end = document.createRange();
    end.selectNodeContents(editor);
    end.collapse(false);
    return end;
  }
  const range = document.createRange();
  if (closest.before) range.setStartBefore(closest.element);
  else range.setStartAfter(closest.element);
  range.collapse(true);
  return range;
}

function placeCaretAfterInlineImage(figure: HTMLElement): void {
  const target = figure.closest<HTMLElement>(".inline-image-row") ?? figure;
  const spacer = createInlineImageCaretText();
  target.after(spacer);
  placeCaretAfter(spacer);
}

async function syncInlineImageOrder(preserveEditorDom = false): Promise<void> {
  if (draft.value === null) return;
  const visible = inlineImageIdsFromDom();
  const ordered = Array.from(
    new Set([
      ...visible,
      ...allImageIds.value.filter((id) => !visible.includes(id)),
    ]),
  );
  if (
    ordered.length === allImageIds.value.length &&
    ordered.every((id, index) => id === allImageIds.value[index])
  )
    return;
  imageUpdating.value = true;
  try {
    const saved = await businessApi.drafts.reorderImages(
      draft.value.id,
      ordered,
      draft.value.entityVersion,
    );
    if (preserveEditorDom) applySavedDraftMetadata(saved);
    else {
      draft.value = saved;
      await renderMissingInlineImages();
    }
    pendingImageIds.value = [];
  } finally {
    imageUpdating.value = false;
  }
}

async function generate(): Promise<void> {
  if (draft.value === null) return;
  if (
    sending.value ||
    uploadingImages.value ||
    imageUpdating.value ||
    saving.value
  ) {
    ElMessage.info("当前报告仍在处理或保存，请完成后再确认生成。");
    return;
  }
  if (aiAnalyzing.value) {
    ElMessage.info("AI正在后台分析，请稍后再确认正式报告。");
    return;
  }
  syncReportContentFromDom();
  try {
    await standardConfirm(
      "确认后将生成正式报告并把最终原因沉淀到当前城市经验库。同一报账点和账期只保留一个正式报告。",
      "确认报告",
      {
        type: "warning",
        confirmButtonText: "确认并生成",
        cancelButtonText: "继续检查",
      },
    );
  } catch {
    return;
  }

  generating.value = true;
  try {
    if (
      sending.value ||
      uploadingImages.value ||
      imageUpdating.value ||
      saving.value
    ) {
      ElMessage.info("报告内容发生变化，请完成当前操作后重新确认。");
      return;
    }
    syncReportContentFromDom();
    if (!(await saveDraft(false))) return;
    const report = await businessApi.drafts.generate(
      draft.value.id,
      draft.value.entityVersion,
    );
    await router.replace({
      name: "report-detail",
      params: { reportId: report.id },
      query: { from: "/reports/generate", autoDownload: "word" },
    });
  } catch (error) {
    ElMessage.error(
      error instanceof Error ? error.message : "正式报告生成失败",
    );
  } finally {
    generating.value = false;
  }
}

async function goBack(): Promise<void> {
  if (draft.value !== null && draft.value.formalReportId !== null) {
    syncReportContentFromDom();
    if (await saveDraft(false, true)) {
      await businessApi.drafts.discardUnusedCorrection(draft.value.id).catch(() => false);
    }
  }
  const from =
    typeof route.query.from === "string"
      ? route.query.from.replace(/\/correction(?=([?#]|$))/, "")
      : "/reports/generate";
  await router.push(from);
}

onMounted(load);
onUnmounted(stopAnalysisPolling);
</script>

<template>
  <PageState v-if="loading" kind="loading" />
  <PageState
    v-else-if="errorMessage"
    kind="error"
    title="草稿加载失败"
    :description="errorMessage"
    @retry="load"
  />

  <template v-else-if="draft">
    <section class="draft-summary business-card">
      <div>
        <small>报账点</small>
        <strong>{{ billingPointLabel }}</strong>
      </div>
      <div>
        <small>超标类型</small>
        <OverLimitTypeTags
          :ratios="draft.overLimitRatios"
          :fallback="draft.overLimitType"
        />
      </div>
      <div>
        <small>超标比例</small>
        <OverLimitRatioTags :ratios="draft.overLimitRatios" />
      </div>
    </section>

    <div
      v-if="draft.status === 'AI_ANALYZING' && hasSubmittedAiAnalysis"
      class="draft-analysis-alert"
      title="AI正在后台分析"
    >
      <ElAlert
        type="info"
        title="AI正在后台分析"
        :closable="false"
        show-icon
      />
    </div>

    <div
      v-else-if="draft.status === 'AI_COMPLETED' && hasSubmittedAiAnalysis"
      class="draft-analysis-alert"
      title="AI分析完成，草稿已更新，请检查修改后再确认导出 Word。"
    >
      <ElAlert
        type="success"
        title="AI分析完成，草稿已更新，请检查修改后再确认导出 Word。"
        :closable="false"
        show-icon
      />
    </div>

    <div
      v-else-if="draft.status === 'AI_FAILED' && hasSubmittedAiAnalysis"
      class="draft-analysis-alert"
      :title="analysisFailedTitle"
    >
      <ElAlert
        type="error"
        :title="analysisFailedTitle"
        :closable="false"
        show-icon
      />
    </div>

    <div
      class="draft-workspace"
      :class="{ 'assistant-open': assistantVisible }"
    >
      <article
        v-if="htmlReportBlock"
        :key="`html-${editorRenderKey}`"
        ref="htmlReportRef"
        class="report-paper html-report-paper business-card"
        aria-label="可编辑报告正文"
        contenteditable="true"
        spellcheck="false"
        v-html="sanitizeEditableHtml(htmlReportBlock.content)"
        @blur="handleEditorBlur"
        @paste="pasteImages"
        @click="selectInlineImage"
        @keyup="rememberTextSelection"
        @mouseup="rememberTextSelection"
        @input="rememberTextSelection"
        @keydown="handleEditorKeydown"
        @dragstart.prevent
        @pointerdown="startInlineImagePointer"
        @pointermove="moveInlineImagePointer"
        @pointerup="finishInlineImagePointer"
        @pointercancel="cancelInlineImagePointer"
      />

      <article
        v-else
        :key="`blocks-${editorRenderKey}`"
        ref="reportPaperRef"
        class="report-paper business-card"
        aria-label="可编辑报告正文"
      >
        <h1
          v-if="headingBlock"
          :data-block-id="headingBlock.id"
          contenteditable="true"
          spellcheck="false"
          v-html="editableBlockHtml(headingBlock)"
          @blur="handleEditorBlur"
          @paste="pasteImages"
          @click="selectInlineImage"
          @keyup="rememberTextSelection"
          @mouseup="rememberTextSelection"
          @input="rememberTextSelection"
          @keydown="handleEditorKeydown"
          @dragstart.prevent
          @pointerdown="startInlineImagePointer"
          @pointermove="moveInlineImagePointer"
          @pointerup="finishInlineImagePointer"
          @pointercancel="cancelInlineImagePointer"
        />

        <section v-for="(block, index) in bodyBlocks" :key="block.id">
          <h2>{{ chineseNumbers[index] ?? index + 1 }}、{{ block.title }}</h2>
          <div
            :data-block-id="block.id"
            class="editable-report-block"
            contenteditable="true"
            spellcheck="false"
            v-html="editableBlockHtml(block)"
            @blur="handleEditorBlur"
            @paste="pasteImages"
            @click="selectInlineImage"
            @keyup="rememberTextSelection"
            @mouseup="rememberTextSelection"
            @input="rememberTextSelection"
            @keydown="handleEditorKeydown"
            @dragstart.prevent
            @pointerdown="startInlineImagePointer"
            @pointermove="moveInlineImagePointer"
            @pointerup="finishInlineImagePointer"
            @pointercancel="cancelInlineImagePointer"
          />
        </section>
      </article>

      <aside v-if="assistantVisible" class="assistant-panel business-card">
        <header>
          <div>
            <h2>AI报告助手</h2>
            <small
              >分析图片后自动补充报告内容，人工确认后再生成正式报告。</small
            >
          </div>
        </header>

        <ElAlert
          v-if="assistantError"
          class="assistant-error"
          type="error"
          title="AI 助手处理失败"
          :description="assistantError"
          show-icon
          closable
          @close="assistantError = ''"
        />

        <div class="chat-list">
          <ElEmpty
            v-if="draft.messages.length === 0"
            description="可在底部输入问题或修改指令"
          />
          <div
            v-for="message in draft.messages"
            v-else
            :key="message.id"
            class="chat-message"
            :class="message.role.toLowerCase()"
          >
            <span>{{ message.role === "USER" ? "您" : "AI" }}</span>
            <div>
              <p>{{ message.content }}</p>
              <small>
                {{ message.createdAt.slice(11, 16) }} ·
                {{
                  message.intent === "ASK"
                    ? "仅问答，正文未变"
                    : message.intent === "EDIT"
                      ? "正文已创建新版本"
                      : message.intent === "CORRECTION"
                        ? "纠错已创建新版本"
                        : "系统消息"
                }}
              </small>
            </div>
          </div>
        </div>

        <div class="prompt-box">
          <div class="prompt-mode">
            <span>本次操作</span>
            <ElSelect
              v-model="assistantIntent"
              size="small"
              aria-label="AI 助手操作类型"
            >
              <ElOption label="智能判断" value="AUTO" />
              <ElOption label="仅回答问题" value="ASK" />
              <ElOption label="修改报告" value="EDIT" />
              <ElOption label="人工纠错并记忆" value="CORRECTION" />
            </ElSelect>
          </div>
          <ElInput
            v-model="prompt"
            type="textarea"
            :rows="2"
            maxlength="1000"
            show-word-limit
            placeholder="输入问题或修改指令；Enter 发送，Shift+Enter 换行"
            :disabled="sending"
            @keydown.enter.exact.prevent="send(assistantIntent)"
          />
          <ElButton
            circle
            type="primary"
            :icon="Promotion"
            :loading="chatSending"
            :disabled="sending || !prompt.trim()"
            aria-label="发送"
            @click="send(assistantIntent)"
          />
        </div>
      </aside>
    </div>

    <footer class="draft-actions">
      <ElButton @click="goBack">返回</ElButton>
      <ElButton
        type="primary"
        plain
        :loading="analyzingImages"
        :disabled="aiAnalyzing || (sending && !analyzingImages)"
        @click="analyzeAllImages"
      >
        AI分析
      </ElButton>
      <ElButton
        type="primary"
        :loading="generating"
        :disabled="
          !draftEditable ||
          aiAnalyzing ||
          sending ||
          uploadingImages ||
          imageUpdating ||
          saving
        "
        @click="generate"
      >
        确认报告并导出 Word
      </ElButton>
    </footer>
  </template>
</template>

<style scoped>
.draft-summary {
  display: grid;
  grid-template-columns:
    minmax(0, 1fr)
    max-content
    max-content;

  width: 100%;
  gap: 16px;
  align-items: center;

  padding: 14px 18px;
  margin-bottom: 16px;

  overflow: hidden;
  box-sizing: border-box;
}

.draft-analysis-alert {
  height: 34px;
  margin: 6px 0 8px;
  overflow: hidden;
}

.draft-analysis-alert :deep(.el-alert) {
  height: 34px;
  min-height: 34px;
  padding: 0 10px;
}

.draft-analysis-alert :deep(.el-alert__content) {
  min-width: 0;
  overflow: hidden;
}

.draft-analysis-alert :deep(.el-alert__title) {
  display: block;
  max-width: 100%;
  overflow: hidden;
  line-height: 34px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.draft-analysis-alert :deep(.el-alert__description) {
  display: none;
  max-width: 100%;
  margin: 2px 0 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.draft-summary > div {
  display: flex;
  min-width: 0;
  gap: 8px;
  align-items: center;
  white-space: nowrap;
}

.draft-summary > div:first-child {
  width: 100%;
  min-width: 0;
}

.draft-summary small {
  flex: 0 0 auto;
  color: #7d8ca1;
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
}

.draft-summary small::after {
  content: "：";
}

.draft-summary strong {
  min-width: 0;
  color: #001733;
  font-size: 14px;
  font-weight: 700;
  white-space: nowrap;
}

.draft-summary > div:first-child strong {
  flex: 1 1 auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.draft-summary > div:nth-child(2),
.draft-summary > div:nth-child(3) {
  flex: 0 0 auto;
}

.danger-text {
  color: #f5223d !important;
}

.draft-workspace {
  display: grid;
  width: 100%;
  height: clamp(560px, calc(100vh - 190px), 880px);
  min-height: 520px;

  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
  align-items: stretch;
  padding-bottom: 72px;
  overflow: hidden;
  box-sizing: border-box;
}

.draft-workspace.assistant-open {
  grid-template-columns:
    minmax(0, 1fr)
    clamp(280px, 31vw, 430px);
}

.draft-workspace > * {
  min-width: 0;
  align-self: stretch;
}

.report-paper {
  position: relative;

  width: 100%;
  height: 100%;

  min-width: 0;
  min-height: 0;

  padding: clamp(18px, 3vw, 28px) clamp(16px, 4vw, 36px) 42px;
  overflow-x: hidden;
  overflow-y: auto;
  background: #fff;
  box-sizing: border-box;
}

.report-paper h1 {
  min-height: 38px;
  margin: 0 0 28px;
  color: #101827;
  font-size: 24px;
  line-height: 1.6;
  text-align: center;
}

.report-paper h2 {
  margin: 22px 0 12px;
  color: #101827;
  font-size: 18px;
}

.editable-report-block {
  min-height: 84px;
  padding: 6px 8px;
  margin: 0;
  color: #1f2d3d;
  line-height: 2;
  white-space: pre-wrap;
  border: 1px solid transparent;
  border-radius: 4px;
}

.html-report-paper {
  width: 100%;
  min-width: 0;
  margin: 0;
  color: #001733;
  line-height: 1.9;
}

.html-report-paper :deep(h1) {
  margin: 0 0 28px;
  text-align: center;
  font-size: 24px;
  font-weight: 800;
}

.html-report-paper :deep(h2) {
  margin: 26px 0 12px;
  font-size: 18px;
  font-weight: 800;
}

.html-report-paper :deep(p) {
  margin: 10px 0;
  text-indent: 2em;
  white-space: pre-wrap;
}

.html-report-paper :deep(.word-preview p),
.html-report-paper :deep(.word-preview > p:first-child) {
  text-indent: 0;
}

.html-report-paper :deep(strong),
.html-report-paper :deep(b) {
  font-weight: 800;
}

.html-report-paper :deep(table) {
  width: 100%;
  max-width: 100%;

  margin: 12px 0;

  table-layout: fixed;
  border-collapse: collapse;
}

.html-report-paper :deep(td),
.html-report-paper :deep(th) {
  overflow-wrap: anywhere;
  word-break: break-word;
}

.html-report-paper :deep(td),
.html-report-paper :deep(th) {
  padding: 6px 8px;
  border: 1px solid #d8e0eb;
}

.html-report-paper :deep(img) {
  display: block;
  max-width: 100%;
  height: auto;
  margin: 12px auto;
  object-fit: contain;
}

.html-report-paper :deep(.word-inline-image),
.html-report-paper :deep(.word-inline-image img) {
  display: inline-block;
  max-width: 100%;
  margin: 0 8px 8px 0;
  vertical-align: top;
}

.report-paper :deep(.inline-image-row),
.html-report-paper :deep(.inline-image-row) {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: flex-start;
  max-width: 100%;
  margin: 8px 0;
}

.report-paper :deep(.inline-image-row .inline-report-image),
.html-report-paper :deep(.inline-image-row .inline-report-image) {
  margin: 0;
}

.report-paper :deep(.inline-image-row img),
.html-report-paper :deep(.inline-image-row img) {
  margin: 0;
}

.report-paper h1[contenteditable="true"],
.html-report-paper[contenteditable="true"],
.editable-report-block[contenteditable="true"] {
  cursor: text;
  outline: none;
}

.report-paper h1[contenteditable="true"]:focus,
.html-report-paper[contenteditable="true"]:focus,
.editable-report-block[contenteditable="true"]:focus {
  background: #fffdfd;
  border-color: #ffb8c1;
  box-shadow: 0 0 0 3px rgb(237 36 55 / 8%);
}

.report-paper :deep(.inline-report-image) {
  position: relative;
  display: inline-block;
  max-width: 100%;
  margin: 6px 8px 6px 0;
  overflow: visible;
  text-align: left;
  vertical-align: top;
  cursor: move;
  user-select: none;
  touch-action: none;
}

.report-paper :deep(.inline-report-image img) {
  display: block;
  width: auto;
  max-width: 100%;
  height: auto !important;
  margin: 0;
  object-fit: contain;
}

.report-paper :deep(.inline-report-image:focus),
.report-paper :deep(.inline-report-image img:focus) {
  outline: none;
}

.report-paper :deep(.inline-report-image.is-selected) {
  outline: 1px solid #3b82f6;
  outline-offset: 2px;
}

.report-paper :deep(.inline-report-image.is-dragging) {
  opacity: 0.58;
}

.report-paper :deep(.inline-image-resize-handle) {
  position: absolute;
  z-index: 2;
  width: 8px;
  height: 8px;
  background: #fff;
  border: 1px solid #2563eb;
  border-radius: 50%;
  box-sizing: border-box;
}

.report-paper :deep(.inline-image-resize-handle.nw) {
  top: -5px;
  left: -5px;
  cursor: nwse-resize;
}

.report-paper :deep(.inline-image-resize-handle.ne) {
  top: -5px;
  right: -5px;
  cursor: nesw-resize;
}

.report-paper :deep(.inline-image-resize-handle.se) {
  right: -5px;
  bottom: -5px;
  cursor: nwse-resize;
}

.report-paper :deep(.inline-image-resize-handle.sw) {
  bottom: -5px;
  left: -5px;
  cursor: nesw-resize;
}

.report-paper :deep(.inline-image-drop-marker) {
  display: inline-block;
  max-width: 100%;
  margin: 6px 8px 6px 0;
  vertical-align: top;
  background: rgb(64 158 255 / 8%);
  border: 1px dashed #409eff;
  box-sizing: border-box;
}

.report-paper :deep(.inline-image-uploading) {
  display: inline-block;
  padding: 8px 12px;
  margin: 4px;
  color: #66768b;
  background: #f2f5f8;
  border-radius: 4px;
}

.assistant-panel {
  position: static;

  display: flex;

  width: 100%;
  height: 100%;

  min-width: 0;
  min-height: 0;
  flex-direction: column;
  align-self: stretch;
  overflow: hidden;
  background: #fff;
  box-sizing: border-box;
}

.assistant-panel > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 12px 14px 10px;
  border-bottom: 1px solid #edf1f5;
}

.assistant-panel h2 {
  margin: 0 0 2px;
  color: #1f2d3d;
  font-size: 18px;
}

.assistant-panel header small {
  color: #7d8ca1;
  font-size: 12px;
  line-height: 1.35;
}

.chat-list {
  display: flex;

  width: 100%;
  min-width: 0;
  min-height: 0;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 12px;

  padding: 12px 14px;
  overflow-x: hidden;
  overflow-y: auto;
  box-sizing: border-box;
}

.chat-list :deep(.el-empty) {
  --el-empty-padding: 18px 0;
}

.chat-list :deep(.el-empty__image) {
  display: none;
}

.chat-list :deep(.el-empty__description) {
  margin-top: 0;
}

.chat-message {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.chat-message.user {
  flex-direction: row-reverse;
}

.chat-message > span {
  display: grid;
  width: 30px;
  height: 30px;
  flex: none;
  place-items: center;
  color: #fff;
  background: #314760;
  border-radius: 50%;
  font-size: 12px;
}

.chat-message.assistant > span {
  color: #314760;
  background: #edf3fb;
}

.chat-message > div {
  max-width: 84%;
  padding: 12px;
  background: #eaf3ff;
  border-radius: 8px;
}

.chat-message.assistant > div {
  background: #fff1f2;
}

.chat-message p {
  margin: 0 0 5px;
  color: #1f2d3d;

  white-space: pre-wrap;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.chat-message small {
  color: #7d8ca1;
}

.prompt-box {
  position: relative;

  flex: 0 0 auto;

  padding: 10px 12px;

  background: #fff;
  border-top: 1px solid #edf1f5;
}

.prompt-mode {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 6px;
  color: #66768b;
  font-size: 12px;
}

.prompt-mode .el-select {
  width: 150px;
}

.prompt-box .el-button {
  position: absolute;
  right: 20px;
  bottom: 20px;
  width: 34px;
  height: 34px;
}

.prompt-box :deep(.el-textarea__inner) {
  min-height: 70px !important;
  padding-right: 54px;
}

.draft-actions {
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

@media (width <= 1280px) {
  .draft-workspace {
    gap: 12px;
  }

  .draft-workspace.assistant-open {
    grid-template-columns:
      minmax(0, 1fr)
      clamp(250px, 30vw, 360px);
  }

  .report-paper {
    padding: 20px 18px 42px;
  }

  .assistant-panel > header {
    padding: 10px 12px 8px;
  }

  .chat-list {
    gap: 10px;
    padding: 11px 12px;
  }

  .prompt-box {
    padding: 9px 10px;
  }

  .prompt-mode {
    gap: 8px;
  }

  .prompt-mode .el-select {
    width: 138px;
  }

  .prompt-box .el-button {
    right: 18px;
    bottom: 18px;
  }
}

@media (width <= 1024px) {
  /*
   * 顶部摘要继续保持紧凑。
   */
  .draft-summary {
    gap: 10px;
    padding-right: 12px;
    padding-left: 12px;
  }

  .draft-summary > div {
    gap: 5px;
  }

  .draft-summary small {
    font-size: 12px;
  }

  .draft-summary strong {
    font-size: 13px;
  }

  /*
   * 内容区仍然左右排列。
   * 只压缩右栏宽度和内部间距。
   */
  .draft-workspace {
    gap: 10px;
  }

  .draft-workspace.assistant-open {
    grid-template-columns:
      minmax(0, 1fr)
      clamp(220px, 29vw, 300px);
  }

  .report-paper {
    padding: 18px 14px 42px;
  }

  .report-paper h1,
  .html-report-paper :deep(h1) {
    font-size: 22px;
  }

  .report-paper h2,
  .html-report-paper :deep(h2) {
    font-size: 17px;
  }

  .assistant-panel > header {
    padding: 9px 10px 7px;
  }

  .assistant-panel h2 {
    font-size: 17px;
  }

  .assistant-panel header small {
    font-size: 12px;
    line-height: 1.3;
  }

  .chat-list {
    gap: 8px;
    padding: 10px;
  }

  .chat-message {
    gap: 7px;
  }

  .chat-message > div {
    max-width: 90%;
    padding: 10px;
  }

  .prompt-box {
    padding: 8px 9px;
  }

  .prompt-mode {
    gap: 6px;
  }

  .prompt-mode .el-select {
    width: 122px;
  }

  .prompt-box .el-button {
    right: 16px;
    bottom: 16px;
  }
}

@media (width <= 760px) {
  /*
   * 小屏仍然保持“左正文 + 右 AI”。
   * 不产生横向滚动，也不把 AI 放到下面。
   */
  .draft-workspace {
    gap: 8px;
  }

  .draft-workspace.assistant-open {
    grid-template-columns:
      minmax(0, 1fr)
      minmax(190px, 36%);
  }

  .report-paper {
    padding: 16px 10px 42px;
  }

  .assistant-panel > header {
    padding: 8px 9px 7px;
  }

  .assistant-panel h2 {
    font-size: 16px;
  }

  .assistant-panel header small {
    font-size: 11px;
  }

  .chat-list {
    padding: 8px;
  }

  .chat-message > span {
    width: 26px;
    height: 26px;
    font-size: 11px;
  }

  .chat-message > div {
    max-width: 92%;
    padding: 9px;
  }

  .prompt-box {
    padding: 8px;
  }

  .prompt-mode {
    align-items: center;
    flex-direction: row;
  }

  .prompt-mode .el-select {
    width: 110px;
  }

  .prompt-box .el-button {
    right: 14px;
    bottom: 14px;
  }
}

@media (width <= 640px) {
  /*
   * 顶部摘要可拆成两行；
   * 下方内容区仍保持左右布局。
   */
  .draft-summary {
    grid-template-columns:
      minmax(0, 1fr)
      max-content;

    gap: 8px;
  }

  .draft-summary > div:first-child {
    grid-column: 1 / -1;
  }

  .draft-summary > div:nth-child(3) {
    justify-self: end;
  }

  .draft-workspace.assistant-open {
    grid-template-columns:
      minmax(0, 1fr)
      minmax(170px, 38%);
  }

  .draft-actions {
    right: 12px;
    left: 12px;
    gap: 8px;
    flex-wrap: wrap;
  }

  .draft-actions .el-button {
    width: auto;
  }
}
</style>
