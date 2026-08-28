<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { EditorContent, useEditor, type Editor } from "@tiptap/vue-3";
import StarterKit from "@tiptap/starter-kit";
import TextAlign from "@tiptap/extension-text-align";
import { Promotion } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import { useRoute, useRouter } from "vue-router";

import { businessApi } from "@/api/business-api";
import { ApiProblem } from "@/api/problem-details";
import OverLimitRatioTags from "@/components/business/OverLimitRatioTags.vue";
import OverLimitTypeTags from "@/components/business/OverLimitTypeTags.vue";
import PageState from "@/components/PageState.vue";
import type { ReportDraft } from "@/types/business";
import { standardConfirm } from "@/utils/message-box";

import {
  ReportImageFigure,
  draftToEditorSections,
  imageFileIdsFromHtml,
  imageUrl,
  sanitizeReportHtml,
  type InlineImageSize,
  type RichEditorSectionKey,
  type RichEditorSections,
  sectionEditorHtmlToBlocks,
} from "./report-rich-editor";

type ExistingReportNode = {
  attrs: Record<string, unknown>;
  childCount?: number;
  nodeSize: number;
  type: { name: string };
  child?: (index: number) => ExistingReportNode;
};

type ReportEditorDoc = {
  descendants: (
    callback: (node: ExistingReportNode, pos: number) => boolean | void,
  ) => void;
};

const route = useRoute();
const router = useRouter();

const draft = ref<ReportDraft | null>(null);
const loading = ref(true);
const saving = ref(false);
const sending = ref(false);
const chatSending = ref(false);
const analyzingImages = ref(false);
const uploadingImages = ref(false);
const imageUpdating = ref(false);
const generating = ref(false);
const errorMessage = ref("");
const prompt = ref("");
const assistantIntent = ref<"AUTO" | "ASK" | "EDIT" | "CORRECTION">("AUTO");
const assistantError = ref("");
const assistantVisible = ref(true);
const selectedFigure = ref<HTMLElement | null>(null);

let saveInFlight: Promise<boolean> | null = null;
let autoSaveTimer: ReturnType<typeof window.setTimeout> | null = null;
let draftDirty = false;
let saveQueued = false;
let applyingRemoteDraft = false;
let analysisPollTimer: ReturnType<typeof window.setTimeout> | null = null;
let analysisPolling = false;
let resizeState: {
  figure: HTMLElement;
  pointerId: number;
  startX: number;
  startWidth: number;
  aspectRatio: number;
  direction: -1 | 1;
} | null = null;

const DEFAULT_PASTED_IMAGE_WIDTH = 320;
const DEFAULT_PASTED_IMAGE_HEIGHT = 180;
const A4_CONTENT_WIDTH = 682;
const INLINE_IMAGE_GAP = 8;
const AUTO_SAVE_DELAY_MS = 800;
const DRAFT_PAGE_SCROLL_LOCK_CLASS = "draft-page-scroll-locked";

const draftEditable = computed(
  () =>
    draft.value !== null &&
    ["EDITING", "AI_COMPLETED", "AI_FAILED"].includes(draft.value.status),
);
const aiAnalyzing = computed(() => draft.value?.status === "AI_ANALYZING");
const hasSubmittedAiAnalysis = computed(
  () => draft.value?.analysisSubmittedAt != null,
);
const allImageIds = computed(() => draft.value?.imageFileIds ?? []);
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

const AI_ANALYSIS_ERROR_MESSAGES: Record<string, string> = {
  AI_IMAGE_ANALYSIS_FAILED: "AI图片分析失败，请检查密钥配置或稍后重新分析。",
  KIMI_AUTH_FAILED: "Kimi 密钥无效或无权限，请检查 KIMI_API_KEY。",
  KIMI_MODEL_UNAVAILABLE: "Kimi 模型不可用，请检查 KIMI_MODEL 配置。",
  KIMI_RATE_LIMIT: "Kimi 当前繁忙或限流，请稍后重新分析。",
  KIMI_TIMEOUT: "Kimi 调用超时，请稍后重新分析。",
  KIMI_IMAGE_INVALID: "图片过大或格式不支持，请减少图片数量或重新粘贴。",
  AI_RESPONSE_INVALID: "Kimi 返回内容格式不符合要求，请重新分析。",
};

const SECTION_CONFIG = [
  { key: "situation", title: "一、情况说明" },
  { key: "analysis", title: "二、排查分析" },
  { key: "rectification", title: "三、整改小结" },
] as const satisfies ReadonlyArray<{
  key: RichEditorSectionKey;
  title: string;
}>;

function createSectionEditor() {
  return useEditor({
    extensions: [
      StarterKit.configure({ heading: false }),
      TextAlign.configure({ types: ["paragraph"] }),
      ReportImageFigure,
    ],
    content: "",
    editable: false,
    editorProps: {
      attributes: {
        class: "report-editor-content",
        spellcheck: "false",
      },
    },
    onUpdate: () => {
      if (!applyingRemoteDraft) markDraftDirty();
    },
  });
}

const situationEditor = createSectionEditor();
const analysisEditor = createSectionEditor();
const rectificationEditor = createSectionEditor();
const sectionEditors = {
  situation: situationEditor,
  analysis: analysisEditor,
  rectification: rectificationEditor,
};

const reportTitle = computed(() => {
  if (draft.value === null) return "电费稽核报告";
  return stripTitleMarks(draftToEditorSections(draft.value).title);
});

const analysisFailedTitle = computed(() => {
  if (draft.value?.status !== "AI_FAILED") return "";
  const message = analysisErrorMessage(draft.value.analysisErrorCode);
  if (message.includes("正文和图片已保留")) {
    return `AI分析失败，${message}`;
  }
  return `AI分析失败，${message}正文和图片已保留。`;
});

watch(draftEditable, (editable) => {
  for (const instance of editableEditors()) instance.setEditable(editable);
});

function analysisErrorMessage(errorCode: string | null | undefined): string {
  if (!errorCode) return "正文和图片已保留，可重新分析。";
  return (
    AI_ANALYSIS_ERROR_MESSAGES[errorCode] ??
    "AI图片分析失败，正文和图片已保留，可重新分析。"
  );
}

function currentEditorHtml(): string {
  const sections = currentEditorSections();
  return sanitizeReportHtml(
    `<article class="confirmed-report-content"><h1>${escapeHtml(sections.title)}</h1>` +
      `<h2>一、情况说明</h2>${sections.situation}` +
      `<h2>二、排查分析</h2>${sections.analysis}` +
      `<h2>三、整改小结</h2>${sections.rectification}</article>`,
  );
}

function currentEditorSections(): RichEditorSections {
  if (draft.value === null) {
    return {
      title: "电费稽核报告",
      situation: "",
      analysis: "",
      rectification: "",
    };
  }
  return {
    title: reportTitle.value,
    situation: sectionHtml("situation"),
    analysis: sectionHtml("analysis"),
    rectification: sectionHtml("rectification"),
  };
}

function syncDraftFromEditor(): void {
  if (draft.value === null) return;
  const sections = currentEditorSections();
  const html = currentEditorHtml();
  draft.value.blocks = sectionEditorHtmlToBlocks(sections, draft.value.blocks);
  const visibleIds = imageFileIdsFromHtml(html);
  draft.value.imageFileIds = draft.value.imageFileIds.filter((id) =>
    visibleIds.includes(id),
  );
}

function setEditorContentFromDraft(loaded: ReportDraft): void {
  const sections = draftToEditorSections(loaded);
  for (const section of SECTION_CONFIG) {
    sectionEditors[section.key].value?.commands.setContent(
      sections[section.key],
      {
        emitUpdate: false,
      },
    );
    sectionEditors[section.key].value?.setEditable(draftEditable.value);
  }
  void nextTick(markImagesReady);
}

function sectionHtml(key: RichEditorSectionKey): string {
  return sanitizeReportHtml(sectionEditors[key].value?.getHTML() ?? "");
}

function editableEditors(): Editor[] {
  return SECTION_CONFIG.map(
    (section) => sectionEditors[section.key].value,
  ).filter((instance): instance is Editor => instance != null);
}

function editorForElement(element: Element): Editor | null {
  for (const instance of editableEditors()) {
    if (instance.view.dom.contains(element)) return instance;
  }
  return null;
}

function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function stripTitleMarks(value: string): string {
  return value
    .trim()
    .replace(/^《+\s*/, "")
    .replace(/\s*》+$/, "");
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

function applyRemoteDraftMetadata(loaded: ReportDraft): void {
  if (draft.value === null || draft.value.id !== loaded.id) return;
  draft.value.status = loaded.status;
  draft.value.analysisStatus = loaded.analysisStatus;
  draft.value.analysisTaskId = loaded.analysisTaskId;
  draft.value.analysisErrorCode = loaded.analysisErrorCode;
  draft.value.analysisSubmittedAt = loaded.analysisSubmittedAt;
  draft.value.analysisCompletedAt = loaded.analysisCompletedAt;
  draft.value.messages = loaded.messages;
  draft.value.currentVersion = loaded.currentVersion;
  draft.value.updatedAt = loaded.updatedAt;
  draft.value.formalReportId = loaded.formalReportId;
  draft.value.entityVersion = loaded.entityVersion;
}

function clearAutoSaveTimer(): void {
  if (autoSaveTimer !== null) {
    window.clearTimeout(autoSaveTimer);
    autoSaveTimer = null;
  }
}

function markDraftDirty(scheduleSave = true): void {
  if (draft.value === null || !draftEditable.value) return;
  draftDirty = true;
  syncDraftFromEditor();
  if (saveInFlight !== null) saveQueued = true;
  if (!scheduleSave) return;
  clearAutoSaveTimer();
  autoSaveTimer = window.setTimeout(() => {
    autoSaveTimer = null;
    void flushDraftSave(false);
  }, AUTO_SAVE_DELAY_MS);
}

async function saveDraftLoop(showSuccess: boolean): Promise<boolean> {
  saving.value = true;
  let shouldShowSuccess = showSuccess;
  try {
    do {
      saveQueued = false;
      if (draft.value === null) return false;
      syncDraftFromEditor();
      draftDirty = false;
      const saved = await businessApi.drafts.save(draft.value.id, draft.value);
      applySavedDraftMetadata(saved);
      if (shouldShowSuccess) ElMessage.success("报告内容已保存。");
      shouldShowSuccess = false;
    } while (saveQueued || draftDirty);
    return true;
  } catch (error) {
    ElMessage.error(
      error instanceof Error ? error.message : "报告内容保存失败",
    );
    return false;
  } finally {
    saving.value = false;
  }
}

async function flushDraftSave(showSuccess = false): Promise<boolean> {
  if (draft.value === null || !draftEditable.value) {
    draftDirty = false;
    saveQueued = false;
    clearAutoSaveTimer();
    return true;
  }
  clearAutoSaveTimer();
  syncDraftFromEditor();
  if (saveInFlight !== null) {
    if (draftDirty) saveQueued = true;
    return saveInFlight;
  }
  if (!draftDirty) return true;
  const operation = saveDraftLoop(showSuccess);
  saveInFlight = operation;
  try {
    return await operation;
  } finally {
    saveInFlight = null;
  }
}

async function waitForPendingSave(): Promise<boolean> {
  if (draftDirty || autoSaveTimer !== null) return flushDraftSave(false);
  return saveInFlight === null ? true : saveInFlight;
}

function handleBeforeUnload(event: BeforeUnloadEvent): void {
  if (!draftDirty && saveInFlight === null && autoSaveTimer === null) return;
  syncDraftFromEditor();
  event.preventDefault();
  event.returnValue = "";
}

async function withRemoteDraftApplication(
  work: () => Promise<void> | void,
): Promise<void> {
  applyingRemoteDraft = true;
  try {
    await work();
  } finally {
    applyingRemoteDraft = false;
  }
}

async function loadEditorContentFromDraft(loaded: ReportDraft): Promise<void> {
  await withRemoteDraftApplication(async () => {
    setEditorContentFromDraft(loaded);
    await nextTick();
  });
}

/*
 * Expose a tiny test surface so unit tests can drive the Tiptap editors without
 * depending on DOM selection details.
 */
defineExpose({
  __testing: {
    sectionEditors,
    applyRemoteDraft,
    flushDraftSave,
  },
});

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
    await applyRemoteDraft(
      loaded,
      !draftDirty && saveInFlight === null,
      draftId,
    );
    if (previousStatus === "AI_ANALYZING" && loaded.status === "AI_COMPLETED") {
      ElMessage.success("AI分析完成，待人工确认。");
    } else if (
      previousStatus === "AI_ANALYZING" &&
      loaded.status === "AI_FAILED"
    ) {
      ElMessage.error(analysisErrorMessage(loaded.analysisErrorCode));
    }
  } catch {
    // Keep polling quietly; the next successful request reconciles state.
  } finally {
    analysisPolling = false;
    syncAnalysisPolling();
  }
}

async function load(): Promise<void> {
  resetTransientState();
  loading.value = true;
  errorMessage.value = "";
  try {
    const draftId = String(route.params.draftId);
    const loaded = await businessApi.drafts.get(draftId);
    if (loaded === undefined) throw new Error("草稿不存在或无权访问");
    await applyRemoteDraft(loaded, true, draftId);
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

function resetTransientState(): void {
  stopAnalysisPolling();
  clearAutoSaveTimer();
  analysisPolling = false;
  draftDirty = false;
  saveQueued = false;
  selectedFigure.value = null;
  resizeState = null;
  assistantError.value = "";
  sending.value = false;
  chatSending.value = false;
  analyzingImages.value = false;
  uploadingImages.value = false;
  imageUpdating.value = false;
}

async function applyRemoteDraft(
  loaded: ReportDraft,
  refreshEditor: boolean,
  expectedDraftId = loaded.id,
): Promise<void> {
  if (
    loaded.id !== expectedDraftId ||
    String(route.params.draftId ?? "") !== expectedDraftId
  ) {
    return;
  }
  const keepLocalEditor =
    refreshEditor &&
    (draftDirty || saveInFlight !== null || autoSaveTimer !== null);
  if (keepLocalEditor && draft.value !== null) {
    applyRemoteDraftMetadata(loaded);
    return;
  }
  draft.value = loaded;
  draftDirty = false;
  saveQueued = false;
  if (refreshEditor) {
    await loadEditorContentFromDraft(loaded);
  }
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
  if (intent === "IMAGE_ANALYSIS") analyzingImages.value = true;
  else chatSending.value = true;

  try {
    if (!(await flushDraftSave(false))) return false;
    const draftId = draft.value.id;
    const loaded = await businessApi.drafts.sendMessage(
      draftId,
      {
        intent,
        content: content || "分析现场图片，补充问题原因、整改建议和报告结论。",
        imageNames: imageFileIds,
        imageFileIds,
      },
      draft.value.entityVersion,
    );
    await applyRemoteDraft(loaded, intent !== "IMAGE_ANALYSIS", draftId);
    syncAnalysisPolling();
    prompt.value = "";
    if (intent !== "IMAGE_ANALYSIS") assistantIntent.value = "AUTO";
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

async function handleEditorPaste(
  event: ClipboardEvent,
  sectionKey: RichEditorSectionKey,
): Promise<void> {
  if (!draftEditable.value || draft.value === null) return;
  const files = Array.from(event.clipboardData?.items ?? [])
    .filter((item) => item.type.startsWith("image/"))
    .map((item) => item.getAsFile())
    .filter((file): file is File => file !== null);
  if (files.length === 0) return;
  event.preventDefault();
  const editorInstance = sectionEditors[sectionKey].value;
  if (editorInstance == null) return;
  const sizes = await pastedImageDisplaySizes(files, editorInstance);
  const uploaded = await addImages(files);
  if (uploaded.length === 0) return;
  insertImageNodes(editorInstance, uploaded, sizes);
  markImagesReady();
  markDraftDirty(false);
  if (await flushDraftSave(false)) {
    ElMessage.success(`已在光标位置粘贴 ${uploaded.length} 张图片。`);
  }
}

function imageFigureNode(fileId: string, size: InlineImageSize | undefined) {
  return {
    type: "reportImageFigure",
    attrs: {
      fileId,
      displayWidth: size === undefined ? null : String(Math.round(size.width)),
      displayHeight:
        size === undefined ? null : String(Math.round(size.height)),
    },
  };
}

function insertImageNodes(
  editorInstance: Editor,
  fileIds: string[],
  sizes: InlineImageSize[],
): void {
  const figures = fileIds.map((fileId, index) =>
    imageFigureNode(fileId, sizes[index]),
  );
  if (figures.length === 0) return;
  const maxWidth = editorContentMaxWidth(editorInstance);
  let lineWidth = currentImageLineWidth(editorInstance);
  const content = figures.flatMap((figure, index) => {
    const width = numericAttr(figure.attrs.displayWidth) ?? maxWidth;
    const shouldWrap =
      (lineWidth > 0 && lineWidth + INLINE_IMAGE_GAP + width > maxWidth) ||
      (index > 0 && lineWidth === 0);
    lineWidth =
      (shouldWrap ? 0 : lineWidth) +
      (shouldWrap || lineWidth === 0 ? 0 : INLINE_IMAGE_GAP) +
      width;
    return shouldWrap ? [{ type: "hardBreak" }, figure] : [figure];
  });

  editorInstance
    .chain()
    .focus()
    .insertContent(content.length === 1 ? content[0]! : content)
    .run();
  setSelectionAfterImage(editorInstance, fileIds.at(-1) ?? "");
}

function currentImageLineWidth(editorInstance: Editor): number {
  const { $from } = editorInstance.state.selection;
  const parent = $from.parent as ExistingReportNode;
  if (
    parent.type.name !== "paragraph" ||
    parent.childCount === undefined ||
    parent.child === undefined
  ) {
    return 0;
  }
  const widths: number[] = [];
  for (let index = $from.index() - 1; index >= 0; index -= 1) {
    const child = parent.child(index);
    if (child.type.name === "hardBreak") break;
    if (child.type.name === "reportImageFigure") {
      widths.unshift(numericAttr(child.attrs.displayWidth) ?? A4_CONTENT_WIDTH);
    }
  }
  return widths.reduce(
    (total, width, index) =>
      total + width + (index === 0 ? 0 : INLINE_IMAGE_GAP),
    0,
  );
}

function findNodeByFileId(
  editorInstance: Editor,
  fileId: string,
): { node: ExistingReportNode; pos: number } | null {
  if (!fileId) return null;
  let matched: { node: ExistingReportNode; pos: number } | null = null;
  (editorInstance.state.doc as unknown as ReportEditorDoc).descendants(
    (node, pos) => {
      if (matched !== null) return false;
      if (
        node.type.name === "reportImageFigure" &&
        node.attrs.fileId === fileId
      ) {
        matched = { node, pos };
        return false;
      }
      return true;
    },
  );
  return matched;
}

function setSelectionAfterImage(editorInstance: Editor, fileId: string): void {
  if (!fileId) return;
  const match = findNodeByFileId(editorInstance, fileId);
  if (match !== null) {
    editorInstance.commands.setTextSelection(match.pos + match.node.nodeSize);
    editorInstance.view.focus();
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
      uploadedIds.push(result.fileId);
    }
    return uploadedIds;
  } finally {
    uploadingImages.value = false;
  }
}

async function pastedImageDisplaySizes(
  files: File[],
  editorInstance: Editor,
): Promise<InlineImageSize[]> {
  const maxWidth = editorContentMaxWidth(editorInstance);
  const sizes = await Promise.all(files.map(imageFileDisplaySize));
  return sizes.map((size) => {
    const source = size ?? {
      width: DEFAULT_PASTED_IMAGE_WIDTH,
      height: DEFAULT_PASTED_IMAGE_HEIGHT,
    };
    const width = Math.min(source.width, maxWidth);
    return {
      width,
      height: (width * source.height) / Math.max(1, source.width),
    };
  });
}

function imageFileDisplaySize(
  file: File,
): Promise<InlineImageSize | undefined> {
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file);
    const image = new Image();
    const finish = (size: InlineImageSize | undefined): void => {
      URL.revokeObjectURL(url);
      resolve(size);
    };
    image.onload = () => {
      finish(
        image.naturalWidth > 0 && image.naturalHeight > 0
          ? { width: image.naturalWidth, height: image.naturalHeight }
          : undefined,
      );
    };
    image.onerror = () => finish(undefined);
    image.src = url;
  });
}

function editorContentMaxWidth(editorInstance: Editor): number {
  const editorElement = editorInstance.view.dom as HTMLElement;
  return Math.min(
    A4_CONTENT_WIDTH,
    Math.max(1, (editorElement.clientWidth || A4_CONTENT_WIDTH) - 32),
  );
}

function numericAttr(value: unknown): number | null {
  if (value === null || value === undefined || value === "") return null;
  const number = Number.parseFloat(String(value));
  return Number.isFinite(number) && number > 0 ? number : null;
}

function handleEditorClick(event: MouseEvent): void {
  const target = event.target;
  if (!(target instanceof Element)) {
    clearSelectedFigure();
    return;
  }
  const figure = target.closest<HTMLElement>(".inline-report-image");
  if (figure === null) {
    clearSelectedFigure();
    return;
  }
  selectFigure(figure);
}

function selectFigure(figure: HTMLElement): void {
  clearSelectedFigure();
  selectedFigure.value = figure;
  figure.classList.add("is-selected");
  for (const name of ["nw", "ne", "se", "sw"]) {
    const handle = document.createElement("span");
    handle.className = `inline-image-resize-handle ${name}`;
    handle.dataset.resizeHandle = name;
    const position = resizeHandlePosition(figure, name);
    handle.style.left = `${position.left}px`;
    handle.style.top = `${position.top}px`;
    document.body.append(handle);
  }
}

function clearSelectedFigure(): void {
  selectedFigure.value?.classList.remove("is-selected");
  document
    .querySelectorAll(".inline-image-resize-handle")
    .forEach((handle) => handle.remove());
  selectedFigure.value = null;
}

function resizeHandlePosition(
  figure: HTMLElement,
  name: string,
): { left: number; top: number } {
  const rect = figure.getBoundingClientRect();
  const horizontal = name.includes("w") ? rect.left : rect.right;
  const vertical = name.includes("n") ? rect.top : rect.bottom;
  return {
    left: horizontal + window.scrollX - 4,
    top: vertical + window.scrollY - 4,
  };
}

function startResize(event: PointerEvent): void {
  if (!draftEditable.value) return;
  const target = event.target;
  if (!(target instanceof Element)) return;
  const handle = target.closest<HTMLElement>(".inline-image-resize-handle");
  const figure = handle === null ? null : selectedFigure.value;
  if (handle === null || figure === null) return;
  const image = imageElementForFigure(figure);
  event.preventDefault();
  event.stopPropagation();
  const rect = image.getBoundingClientRect();
  resizeState = {
    figure,
    pointerId: event.pointerId,
    startX: event.clientX,
    startWidth: Math.max(1, rect.width),
    aspectRatio: Math.max(0.01, rect.width / Math.max(1, rect.height)),
    direction: handle.dataset.resizeHandle?.includes("w") ? -1 : 1,
  };
  window.addEventListener("pointermove", moveResize);
  window.addEventListener("pointerup", finishResize);
  window.addEventListener("pointercancel", cancelResize);
  figure.classList.add("is-resizing");
}

function moveResize(event: PointerEvent): void {
  const state = resizeState;
  if (state === null || state.pointerId !== event.pointerId) return;
  event.preventDefault();
  const delta = (event.clientX - state.startX) * state.direction;
  const width = Math.min(
    A4_CONTENT_WIDTH,
    Math.max(80, state.startWidth + delta),
  );
  const height = width / state.aspectRatio;
  applyFigureDisplaySize(state.figure, { width, height });
}

async function finishResize(event: PointerEvent): Promise<void> {
  const state = resizeState;
  if (state === null || state.pointerId !== event.pointerId) return;
  resizeState = null;
  window.removeEventListener("pointermove", moveResize);
  window.removeEventListener("pointerup", finishResize);
  window.removeEventListener("pointercancel", cancelResize);
  state.figure.classList.remove("is-resizing");
  updateFigureNodeAttrs(state.figure);
  markDraftDirty(false);
  await flushDraftSave(false);
}

function cancelResize(event: PointerEvent): void {
  const state = resizeState;
  if (state === null || state.pointerId !== event.pointerId) return;
  resizeState = null;
  window.removeEventListener("pointermove", moveResize);
  window.removeEventListener("pointerup", finishResize);
  window.removeEventListener("pointercancel", cancelResize);
  state.figure.classList.remove("is-resizing");
}

function applyFigureDisplaySize(
  figure: HTMLElement,
  size: InlineImageSize,
): void {
  const image = imageElementForFigure(figure);
  const width = Math.round(size.width * 100) / 100;
  const height = Math.round(size.height * 100) / 100;
  figure.dataset.displayWidth = String(width);
  figure.dataset.displayHeight = String(height);
  figure.style.width = `${width}px`;
  figure.style.minHeight = `${height}px`;
  image.dataset.displayWidth = String(width);
  image.dataset.displayHeight = String(height);
  image.setAttribute("width", String(Math.round(width)));
  image.setAttribute("height", String(Math.round(height)));
  image.style.width = `${width}px`;
  image.style.height = `${height}px`;
  moveResizeHandles(figure);
}

function imageElementForFigure(figure: HTMLElement): HTMLImageElement {
  if (figure instanceof HTMLImageElement) return figure;
  const image = figure.querySelector<HTMLImageElement>("img");
  if (image === null) throw new Error("图片节点缺少 img 元素");
  return image;
}

function moveResizeHandles(figure: HTMLElement): void {
  document
    .querySelectorAll<HTMLElement>(".inline-image-resize-handle")
    .forEach((handle) => {
      const name = handle.dataset.resizeHandle ?? "";
      const position = resizeHandlePosition(figure, name);
      handle.style.left = `${position.left}px`;
      handle.style.top = `${position.top}px`;
    });
}

function updateFigureNodeAttrs(figure: HTMLElement): void {
  const instance = editorForElement(figure);
  if (instance == null) return;
  try {
    const pos = instance.view.posAtDOM(figure, 0);
    const node = instance.state.doc.nodeAt(pos);
    if (node?.type.name !== "reportImageFigure") return;
    instance.view.dispatch(
      instance.state.tr.setNodeMarkup(pos, undefined, {
        ...node.attrs,
        displayWidth: figure.dataset.displayWidth ?? null,
        displayHeight: figure.dataset.displayHeight ?? null,
      }),
    );
  } catch {
    // A stale DOM node can exist briefly after ProseMirror rerenders.
  }
}

function markImagesReady(): void {
  document
    .querySelectorAll<HTMLImageElement>(
      ".report-editor-content img[data-file-id]",
    )
    .forEach((image) => {
      image.onerror = () => {
        const fileId = image.dataset.fileId;
        if (!fileId || image.dataset.usedGenericFallback === "true") return;
        image.dataset.usedGenericFallback = "true";
        image.src = imageUrl(fileId);
      };
    });
}

async function analyzeAllImages(): Promise<void> {
  if (uploadingImages.value || imageUpdating.value) {
    ElMessage.info("图片正在添加或调整，请稍后再分析。");
    return;
  }
  if (sending.value || draft.value === null) return;
  syncDraftFromEditor();
  let imageIds = imageFileIdsFromHtml(currentEditorHtml());
  if (imageIds.length === 0) {
    ElMessage.warning("请先在左侧报告正文中粘贴图片。");
    return;
  }
  if (aiAnalyzing.value) {
    ElMessage.info("AI正在后台分析，请稍后再提交。");
    return;
  }
  if (!(await flushDraftSave(false))) {
    assistantError.value = "当前报告尚未保存，已停止图片分析。";
    return;
  }
  imageIds = imageFileIdsFromHtml(currentEditorHtml());
  const previousPrompt = prompt.value;
  prompt.value =
    "请结合当前图片和业务数据生成电费稽核说明。先仿写同报账点历史稽核报告；同点历史不适用时，再仿写本城市历史正式报告；仿写仍不适用时，再按通用稽核说明规则兜底。保留我粘贴图片的原始位置、单行/并排布局和顺序。";
  const succeeded = await send("IMAGE_ANALYSIS", imageIds);
  if (succeeded) {
    ElMessage.success(
      "AI分析任务已提交，可留在当前页等待，也可返回列表继续处理其他报账点。",
    );
  } else {
    prompt.value = previousPrompt;
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
  syncDraftFromEditor();
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
    syncDraftFromEditor();
    if (!(await flushDraftSave(false)) || draft.value === null) return;
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
  if (draft.value !== null) {
    syncDraftFromEditor();
    if ((await flushDraftSave(false)) && draft.value.formalReportId !== null) {
      await businessApi.drafts
        .discardUnusedCorrection(draft.value.id)
        .catch(() => false);
    }
  }
  const from =
    typeof route.query.from === "string"
      ? route.query.from.replace(/\/correction(?=([?#]|$))/, "")
      : "/reports/generate";
  await router.push(from);
}

onMounted(() => {
  document.body.classList.add(DRAFT_PAGE_SCROLL_LOCK_CLASS);
  window.addEventListener("beforeunload", handleBeforeUnload);
  void load();
});
watch(
  () => String(route.params.draftId ?? ""),
  (_draftId, previousDraftId) => {
    if (previousDraftId === undefined) return;
    void load();
  },
);
onUnmounted(() => {
  document.body.classList.remove(DRAFT_PAGE_SCROLL_LOCK_CLASS);
  stopAnalysisPolling();
  clearAutoSaveTimer();
  window.removeEventListener("beforeunload", handleBeforeUnload);
  for (const instance of editableEditors()) instance.destroy();
});
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

  <div v-else-if="draft" class="draft-page">
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
          quiet
        />
      </div>
      <div>
        <small>超标比例</small>
        <OverLimitRatioTags :ratios="draft.overLimitRatios" quiet />
      </div>
    </section>

    <div
      v-if="draft.status === 'AI_ANALYZING' && hasSubmittedAiAnalysis"
      class="draft-analysis-alert"
      title="AI正在后台分析"
    >
      <ElAlert type="info" title="AI正在后台分析" :closable="false" show-icon />
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
      <article class="report-paper business-card" aria-label="稽核报告草稿">
        <div class="report-scroll">
          <h1 class="report-title">《{{ reportTitle }}》</h1>
          <section
            v-for="section in SECTION_CONFIG"
            :key="section.key"
            class="report-section"
          >
            <h2 class="report-section-title">{{ section.title }}</h2>
            <EditorContent
              v-if="sectionEditors[section.key].value"
              class="report-editor"
              :data-section="section.key"
              :editor="sectionEditors[section.key].value"
              @blur="flushDraftSave(false)"
              @paste="handleEditorPaste($event, section.key)"
              @click="handleEditorClick"
              @pointerdown="startResize"
              @pointermove="moveResize"
              @pointerup="finishResize"
              @pointercancel="cancelResize"
            />
          </section>
        </div>
      </article>

      <aside v-if="assistantVisible" class="assistant-panel business-card">
        <header>
          <div>
            <h2>AI报告助手</h2>
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
  </div>
</template>

<style scoped>
.draft-page {
  display: grid;
  height: 100%;
  min-height: 0;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  overflow: hidden;
}

:global(body.draft-page-scroll-locked) {
  overflow-y: hidden;
}

:global(body.draft-page-scroll-locked .main-content) {
  height: calc(100dvh - var(--topbar-height));
  min-height: 0;
  overflow: hidden;
}

.draft-summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) max-content max-content;
  width: 100%;
  gap: 16px;
  align-items: center;
  padding: 14px 18px;
  margin-bottom: 6px;
  overflow: hidden;
  box-sizing: border-box;
}

.draft-summary > div {
  display: flex;
  min-width: 0;
  gap: 8px;
  align-items: center;
  white-space: nowrap;
}

.draft-summary small {
  flex: 0 0 auto;
  color: #7d8ca1;
  font-size: 13px;
  font-weight: 700;
}

.draft-summary small::after {
  content: "：";
}

.draft-summary strong {
  min-width: 0;
  color: #001733;
  font-size: 14px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
}

.draft-analysis-alert {
  min-height: 34px;
  margin: 0 0 6px;
  overflow: hidden;
}

.draft-analysis-alert :deep(.el-alert) {
  height: 34px;
  min-height: 34px;
  padding: 0 10px;
}

.draft-analysis-alert :deep(.el-alert__title) {
  display: block;
  max-width: 100%;
  overflow: hidden;
  line-height: 34px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.draft-workspace {
  display: grid;
  width: 100%;
  height: auto;
  min-height: 0;
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
  align-items: stretch;
  overflow: hidden;
  box-sizing: border-box;
}

.draft-workspace.assistant-open {
  grid-template-columns: minmax(0, 1fr) clamp(260px, 26vw, 360px);
}

.report-paper {
  position: relative;
  display: flex;
  width: min(920px, 100%);
  height: 100%;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  padding: 0;
  margin: 0 auto;
  overflow: hidden;
  background: #fff;
  box-sizing: border-box;
}

.report-scroll {
  min-height: 0;
  flex: 1 1 auto;
  padding: 48px 56px 64px;
  overflow: auto;
  box-sizing: border-box;
}

.report-title {
  min-height: 38px;
  margin: 0 0 28px;
  color: #101827;
  font-size: 24px;
  font-weight: 800;
  line-height: 1.6;
  text-align: center;
}

.report-section {
  margin: 22px 0 26px;
}

.report-section-title {
  margin: 0 0 10px;
  color: #101827;
  font-size: 18px;
  font-weight: 800;
  line-height: 1.6;
}

.report-editor {
  width: 100%;
  min-height: 84px;
  overflow: hidden;
  border: 1px solid transparent;
  border-radius: 4px;
  background: #fff;
  box-sizing: border-box;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease,
    background-color 0.15s ease;
}

.report-editor:focus-within {
  background: #fffdfd;
  border-color: #ffb8c1;
  box-shadow: 0 0 0 3px rgb(237 36 55 / 8%);
}

.report-editor :deep(.report-editor-content) {
  min-height: 82px;
  padding: 6px 8px;
  color: #1f2d3d;
  line-height: 2;
  outline: none;
  box-sizing: border-box;
}

.report-editor :deep(p) {
  margin: 8px 0;
  text-indent: 2em;
  white-space: pre-wrap;
}

.report-editor :deep(strong),
.report-editor :deep(b) {
  font-weight: 800;
}

.report-editor :deep(table) {
  width: 100%;
  max-width: 100%;
  margin: 12px 0;
  table-layout: fixed;
  border-collapse: collapse;
}

.report-editor :deep(td),
.report-editor :deep(th) {
  padding: 6px 8px;
  overflow-wrap: anywhere;
  border: 1px solid #d8e0eb;
}

.report-editor :deep(.inline-image-row) {
  display: flex;
  flex-wrap: wrap;
  width: 100%;
  min-width: 0;
  gap: 8px;
  align-items: flex-start;
  max-width: 100%;
  margin: 8px 0;
}

.report-editor :deep(.inline-report-image) {
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

.report-editor :deep(.inline-image-row .inline-report-image) {
  flex: 0 0 auto;
  margin: 0;
}

.report-editor :deep(.inline-report-image img) {
  display: inline-block;
  width: auto;
  max-width: min(100%, 682px);
  height: auto;
  margin: 0;
  object-fit: contain;
}

.report-editor :deep(img.inline-report-image) {
  display: inline-block;
  width: auto;
  max-width: min(100%, 682px);
  height: auto;
  margin: 6px 8px 6px 0;
  object-fit: contain;
  vertical-align: bottom;
}

.report-editor :deep(.inline-report-image.is-selected) {
  outline: 1px solid #3b82f6;
  outline-offset: 2px;
}

.report-editor :deep(.inline-report-image.is-resizing) {
  opacity: 0.85;
}

:global(.inline-image-resize-handle) {
  position: absolute;
  z-index: 2;
  width: 8px;
  height: 8px;
  background: #fff;
  border: 1px solid #2563eb;
  border-radius: 50%;
  box-sizing: border-box;
}

:global(.inline-image-resize-handle.nw) {
  cursor: nwse-resize;
}

:global(.inline-image-resize-handle.ne) {
  cursor: nesw-resize;
}

:global(.inline-image-resize-handle.se) {
  cursor: nwse-resize;
}

:global(.inline-image-resize-handle.sw) {
  cursor: nesw-resize;
}

.assistant-panel {
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
  padding: 12px 14px;
  border-bottom: 1px solid #edf1f5;
}

.assistant-panel h2 {
  margin: 0;
  color: #1f2d3d;
  font-size: 18px;
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
  overflow: hidden auto;
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
  display: flex;
  min-height: 58px;
  gap: 12px;
  align-items: center;
  justify-content: flex-end;
  padding: 10px 0 0;
  background: rgb(255 255 255 / 96%);
  border-top: 1px solid #dfe5ec;
}

@media (width <= 1280px) {
  .draft-workspace.assistant-open {
    grid-template-columns: minmax(0, 1fr) clamp(230px, 27vw, 320px);
  }

  .report-scroll {
    padding: 36px 40px 56px;
  }
}

@media (width <= 760px) {
  .draft-workspace.assistant-open {
    grid-template-columns: minmax(0, 1fr) minmax(190px, 36%);
  }

  .draft-actions {
    gap: 8px;
    flex-wrap: wrap;
  }
}
</style>
