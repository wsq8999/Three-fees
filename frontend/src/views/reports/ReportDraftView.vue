<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowLeft, Picture, Promotion } from "@element-plus/icons-vue";
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
const pendingImageIds = ref<string[]>([]);
const uploadingImages = ref(false);
let saveInFlight: Promise<boolean> | null = null;
let imageRemovalInFlight: Promise<boolean> | null = null;
let analysisPollTimer: ReturnType<typeof window.setTimeout> | null = null;
let analysisPolling = false;

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
  const html = looksLikeHtml(block.content)
    ? block.content
    : escapeHtml(block.content).replace(/\r?\n/g, "<br>");
  return sanitizeEditableHtml(html);
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

async function saveDraft(showSuccess = false): Promise<boolean> {
  if (imageRemovalInFlight !== null && !(await imageRemovalInFlight)) {
    return false;
  }
  if (draft.value === null || !draftEditable.value) return false;
  if (saveInFlight !== null) return saveInFlight;
  syncReportContentFromDom();
  const inlineIdsAtSave = new Set(inlineImageIdsFromDom());
  saving.value = true;
  const operation = (async (): Promise<boolean> => {
    try {
      if (draft.value === null) return false;
      draft.value = await businessApi.drafts.save(draft.value.id, draft.value);
      const removedIds = draft.value.imageFileIds.filter(
        (fileId) => !inlineIdsAtSave.has(fileId),
      );
      for (const fileId of removedIds) {
        draft.value = await businessApi.drafts.removeImage(
          draft.value.id,
          fileId,
          draft.value.entityVersion,
        );
        pendingImageIds.value = pendingImageIds.value.filter(
          (pendingId) => pendingId !== fileId,
        );
      }
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
    draft.value = loaded;
    await renderMissingInlineImages();
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
    if (loaded === undefined) throw new Error("工作稿不存在或无权访问");
    draft.value = loaded;
    assistantVisible.value = true;
    if (route.query.action === "image") {
      ElMessage.info("可直接在左侧报告正文任意位置粘贴图片，再点击“分析全部图片”。");
    }
  } catch (error) {
    errorMessage.value =
      error instanceof Error ? error.message : "工作稿加载失败";
  } finally {
    loading.value = false;
  }
  if (draft.value !== null) await renderMissingInlineImages();
  syncAnalysisPolling();
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
    draft.value = await businessApi.drafts.sendMessage(
      draft.value.id,
      {
        intent,
        content: content || "分析现场图片，补充问题原因、整改建议和报告结论。",
        imageNames: imageFileIds,
        imageFileIds,
      },
      draft.value.entityVersion,
    );
    await renderMissingInlineImages();
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
          : "AI 请求失败，工作稿未修改";
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
  const markers = insertUploadMarkers(editor, files.length);
  let uploadedCount = 0;
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
        const spacer = createInlineImageSpacer();
        marker.replaceWith(createInlineImage(fileId), spacer);
        placeCaretAfter(spacer);
        uploadedCount++;
      } catch (error) {
        marker.remove();
        ElMessage.error(
          error instanceof Error ? error.message : "粘贴图片失败",
        );
      }
    }
    if (uploadedCount > 0) {
      syncReportContentFromDom();
      if (await saveDraft(false)) {
        await syncInlineImageOrder();
      }
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
  const selection = window.getSelection();
  const range = document.createRange();
  if (
    selection !== null &&
    selection.rangeCount > 0 &&
    editor.contains(selection.getRangeAt(0).commonAncestorContainer)
  ) {
    range.setStart(
      selection.getRangeAt(0).startContainer,
      selection.getRangeAt(0).startOffset,
    );
    range.setEnd(
      selection.getRangeAt(0).endContainer,
      selection.getRangeAt(0).endOffset,
    );
    range.deleteContents();
  } else {
    range.selectNodeContents(editor);
    range.collapse(false);
  }
  const markers: HTMLElement[] = [];
  for (let index = 0; index < count; index++) {
    const marker = document.createElement("span");
    marker.className = "inline-image-uploading";
    marker.contentEditable = "false";
    marker.textContent = "图片上传中…";
    range.insertNode(marker);
    range.setStartAfter(marker);
    range.collapse(true);
    markers.push(marker);
  }
  selection?.removeAllRanges();
  selection?.addRange(range);
  return markers;
}

function createInlineImage(fileId: string): HTMLElement {
  const figure = document.createElement("figure");
  figure.className = "inline-report-image";
  figure.dataset.fileId = fileId;
  figure.contentEditable = "false";
  const image = document.createElement("img");
  image.src = imageUrl(fileId);
  image.alt = "稽核证据图片";
  image.dataset.fileId = fileId;
  image.draggable = false;
  attachImageFallback(image);
  figure.append(image);
  return figure;
}

function createInlineImageSpacer(): HTMLBRElement {
  const spacer = document.createElement("br");
  spacer.dataset.inlineImageSpacer = "true";
  return spacer;
}

function placeCaretAfter(node: Node): void {
  const selection = window.getSelection();
  if (selection === null) return;
  const range = document.createRange();
  range.setStartAfter(node);
  range.collapse(true);
  selection.removeAllRanges();
  selection.addRange(range);
}

async function addImages(files: File[]): Promise<string[]> {
  if (draft.value === null) throw new Error("工作稿尚未加载完成");
  if (uploadingImages.value || imageUpdating.value || sending.value) {
    throw new Error("当前操作尚未完成，请稍后再添加图片");
  }
  const accepted = files.filter((file) =>
    ["image/png", "image/jpeg"].includes(file.type),
  );
  if (accepted.length !== files.length)
    throw new Error("仅支持 PNG 或 JPEG 图片");
  if (allImageIds.value.length + accepted.length > 10)
    throw new Error("一份报告最多包含 10 张图片");
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
    "按真实电费稽核说明生成；仅在排查分析中为设备图、机房图上方补充简短说明，格式为“说明文字：”后紧跟图片；情况说明和其他类型图片不加说明；正文只写业务判断，不写同点历史、本市经验、外市参考等内部来源话术。";
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
  root
    .querySelectorAll<HTMLImageElement>("figure[data-file-id] img[data-used-generic-fallback]")
    .forEach((image) => delete image.dataset.usedGenericFallback);
}

function normalizeInlineImageElements(root: HTMLElement): void {
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
    figure.classList.remove("is-selected");
    figure.contentEditable = "false";
  });

  root.querySelectorAll<HTMLElement>("figure[data-file-id]").forEach((figure) => {
    const fileId = figure.dataset.fileId;
    if (fileId === undefined || fileId.length === 0) return;
    figure.classList.add("inline-report-image");
    figure.classList.remove("is-selected");
    figure.contentEditable = "false";
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
  });
  ensureInlineImageCaretTargets(root);
}

function ensureInlineImageCaretTargets(root: HTMLElement): void {
  root
    .querySelectorAll<HTMLElement>("figure.inline-report-image[data-file-id]")
    .forEach((figure) => {
      const next = figure.nextSibling;
      if (
        next instanceof HTMLBRElement &&
        next.dataset.inlineImageSpacer === "true"
      ) {
        return;
      }
      figure.after(createInlineImageSpacer());
    });
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
      figure.classList.remove("is-selected");
      figure.contentEditable = "false";
      importedImage.src = imageUrl(fileId);
      importedImage.draggable = false;
      continue;
    }
  }
  normalizeInlineImageElements(root);
}

function selectInlineImage(event: MouseEvent): void {
  const target = event.target;
  const figure =
    target instanceof Element
      ? target.closest<HTMLElement>("figure[data-file-id]")
      : null;
  if (figure === null) return;
  if (event.currentTarget instanceof HTMLElement) {
    event.currentTarget.focus({ preventScroll: true });
  }
  const selection = window.getSelection();
  const range = document.createRange();
  range.selectNode(figure);
  selection?.removeAllRanges();
  selection?.addRange(range);
}

function selectedInlineImageId(): string | null {
  const selection = window.getSelection();
  if (selection === null || selection.rangeCount === 0) return null;
  const fragment = selection.getRangeAt(0).cloneContents();
  return (
    fragment.querySelector<HTMLElement>("figure[data-file-id]")?.dataset
      .fileId ?? null
  );
}

function handleEditorKeydown(event: KeyboardEvent): void {
  if (event.key !== "Backspace" && event.key !== "Delete") return;
  const fileId = selectedInlineImageId();
  if (fileId === null) return;
  event.preventDefault();
  void removeInlineImageFromReport(fileId);
}

async function removeInlineImageFromReport(fileId: string): Promise<boolean> {
  if (imageRemovalInFlight !== null) return imageRemovalInFlight;
  const operation = (async (): Promise<boolean> => {
    if (draft.value === null) return false;
    const draftId = draft.value.id;
    const root = reportEditorRoot();
    root
      ?.querySelectorAll<HTMLElement>(
        `figure[data-file-id="${CSS.escape(fileId)}"]`,
      )
      .forEach((figure) => figure.remove());
    syncReportContentFromDom();
    imageUpdating.value = true;
    try {
      if (!(await waitForPendingSave()) || draft.value === null) return false;
      reportEditorRoot()
        ?.querySelectorAll<HTMLElement>(
          `figure[data-file-id="${CSS.escape(fileId)}"]`,
        )
        .forEach((figure) => figure.remove());
      syncReportContentFromDom();
      draft.value.imageFileIds = draft.value.imageFileIds.filter(
        (id) => id !== fileId,
      );
      pendingImageIds.value = pendingImageIds.value.filter(
        (id) => id !== fileId,
      );
      draft.value = await businessApi.drafts.removeImage(
        draft.value.id,
        fileId,
        draft.value.entityVersion,
      );
      return true;
    } catch (error) {
      const reloaded = await businessApi.drafts
        .get(draftId)
        .catch(() => undefined);
      if (reloaded !== undefined) draft.value = reloaded;
      await renderMissingInlineImages();
      ElMessage.error(error instanceof Error ? error.message : "图片删除失败");
      return false;
    } finally {
      imageUpdating.value = false;
    }
  })();
  imageRemovalInFlight = operation;
  try {
    return await operation;
  } finally {
    imageRemovalInFlight = null;
  }
}

async function syncInlineImageOrder(): Promise<void> {
  if (draft.value === null) return;
  const visible = inlineImageIdsFromDom();
  const ordered = Array.from(
    new Set([
      ...visible,
      ...allImageIds.value.filter((id) => !visible.includes(id)),
    ]),
  );
  if (
    ordered.length !== allImageIds.value.length ||
    ordered.every((id, index) => id === allImageIds.value[index])
  )
    return;
  imageUpdating.value = true;
  try {
    draft.value = await businessApi.drafts.reorderImages(
      draft.value.id,
      ordered,
      draft.value.entityVersion,
    );
    pendingImageIds.value = [];
    await renderMissingInlineImages();
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
  await router.push(
    typeof route.query.from === "string"
      ? route.query.from
      : "/reports/generate",
  );
}

onMounted(load);
onUnmounted(stopAnalysisPolling);
</script>

<template>
  <PageState v-if="loading" kind="loading" />
  <PageState
    v-else-if="errorMessage"
    kind="error"
    title="工作稿加载失败"
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
      v-if="draft.status === 'AI_ANALYZING'"
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
      v-else-if="draft.status === 'AI_COMPLETED'"
      class="draft-analysis-alert"
      title="AI分析完成，工作稿已更新，请检查修改后再确认导出 Word。"
    >
      <ElAlert
        type="success"
        title="AI分析完成，工作稿已更新，请检查修改后再确认导出 Word。"
        :closable="false"
        show-icon
      />
    </div>

    <div
      v-else-if="draft.status === 'AI_FAILED'"
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
        ref="htmlReportRef"
        class="report-paper html-report-paper business-card"
        aria-label="可编辑报告正文"
        contenteditable="true"
        spellcheck="false"
        v-html="sanitizeEditableHtml(htmlReportBlock.content)"
        @blur="saveDraft(false)"
        @paste="pasteImages"
        @click="selectInlineImage"
        @keydown="handleEditorKeydown"
      />

      <article
        v-else
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
          @blur="saveDraft(false)"
          @paste="pasteImages"
          @click="selectInlineImage"
          @keydown="handleEditorKeydown"
        />

        <section v-for="(block, index) in bodyBlocks" :key="block.id">
          <h2>{{ chineseNumbers[index] ?? index + 1 }}、{{ block.title }}</h2>
          <div
            :data-block-id="block.id"
            class="editable-report-block"
            contenteditable="true"
            spellcheck="false"
            v-html="editableBlockHtml(block)"
            @blur="saveDraft(false)"
            @paste="pasteImages"
            @click="selectInlineImage"
            @keydown="handleEditorKeydown"
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
            description="点击底部“分析图片”后，AI 助手将在这里给出处理过程"
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
                      : message.intent === "IMAGE_ANALYSIS"
                        ? "图片分析已回填正文"
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
      <ElButton :icon="ArrowLeft" @click="goBack">返回</ElButton>
      <ElButton
        type="primary"
        plain
        :icon="Picture"
        :loading="analyzingImages"
        :disabled="aiAnalyzing || (sending && !analyzingImages)"
        @click="analyzeAllImages"
      >
        分析全部图片
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
}

.html-report-paper :deep(h2) {
  margin: 24px 0 10px;
  font-size: 18px;
}

.html-report-paper :deep(p) {
  margin: 8px 0;
  white-space: pre-wrap;
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
  max-width: 100%;
  height: auto;
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
  display: block;
  width: min(100%, 680px);
  margin: 14px auto;
  overflow: hidden;
}

.report-paper :deep(.inline-report-image img) {
  display: block;
  width: 100%;
  max-height: 420px;
  object-fit: contain;
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
