<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowLeft, Clock, Picture, Promotion } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";

import { businessApi, triggerBrowserDownload } from "@/api/business-api";
import StatusTag from "@/components/business/StatusTag.vue";
import PageState from "@/components/PageState.vue";
import type { DraftBlock, DraftVersion, ReportDraft } from "@/types/business";
import { standardConfirm } from "@/utils/message-box";

const route = useRoute();
const router = useRouter();

const draft = ref<ReportDraft | null>(null);
const loading = ref(true);
const saving = ref(false);
const sending = ref(false);
const generating = ref(false);
const errorMessage = ref("");
const prompt = ref("");
const assistantError = ref("");
const historyVisible = ref(false);
const assistantVisible = ref(true);
const previewVisible = ref(false);
const previewVersion = ref<DraftVersion | null>(null);
const fileInput = ref<HTMLInputElement>();
const pendingImageIds = ref<string[]>([]);
const uploadingImages = ref(false);

const allImageIds = computed(() =>
  Array.from(new Set([...(draft.value?.imageFileIds ?? []), ...pendingImageIds.value])),
);

const chineseNumbers = ["一", "二", "三", "四", "五", "六", "七", "八"];

const headingBlock = computed(
  () => draft.value?.blocks.find((block) => block.type === "HEADING") ?? null,
);

const bodyBlocks = computed(
  () => draft.value?.blocks.filter((block) => block.type !== "HEADING") ?? [],
);

function editableText(event: Event): string {
  return (event.target as HTMLElement).innerText.trim();
}

function updateBlock(block: DraftBlock, content: string): void {
  if (draft.value === null) return;
  const target = draft.value.blocks.find((item) => item.id === block.id);
  if (target !== undefined) target.content = content;
}

async function saveDraft(showSuccess = false): Promise<void> {
  if (draft.value === null || saving.value || draft.value.status !== "EDITING") return;
  saving.value = true;
  try {
    draft.value = await businessApi.drafts.save(draft.value.id, draft.value);
    if (showSuccess) ElMessage.success("报告内容已保存。");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "报告内容保存失败");
  } finally {
    saving.value = false;
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
      ElMessage.info("可直接在左侧“排查分析”粘贴图片，再点击“分析全部图片”。");
    }
  } catch (error) {
    errorMessage.value =
      error instanceof Error ? error.message : "工作稿加载失败";
  } finally {
    loading.value = false;
  }
}

async function send(
  intent: "AUTO" | "IMAGE_ANALYSIS" = "AUTO",
  imageFileIds: string[] = [],
): Promise<void> {
  if (draft.value === null || sending.value) return;
  const content = prompt.value.trim();
  if (intent === "AUTO" && content.length === 0) return;
  if (intent === "IMAGE_ANALYSIS" && allImageIds.value.length === 0) return;

  assistantVisible.value = true;
  assistantError.value = "";
  sending.value = true;
  try {
    draft.value = await businessApi.drafts.sendMessage(
      draft.value.id,
      {
        intent,
        content:
          content ||
          "分析现场图片，补充问题原因、整改建议和报告结论。",
        imageNames: imageFileIds,
        imageFileIds,
      },
      draft.value.entityVersion,
    );
    prompt.value = "";
    pendingImageIds.value = pendingImageIds.value.filter(
      (id) => !imageFileIds.includes(id),
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : "AI 请求失败，工作稿未修改";
    assistantError.value = message;
    ElMessage.error(message);
  } finally {
    sending.value = false;
  }
}

function chooseImage(): void {
  assistantVisible.value = true;
  fileInput.value?.click();
}

async function imageSelected(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  if (files.length === 0) return;
  try {
    await addImages(files);
    ElMessage.success("图片已加入当前报告，点击“分析全部图片”后 AI 会逐张处理。");
  } catch (error) {
    ElMessage.error(
      error instanceof Error ? error.message : "图片加入报告失败",
    );
  } finally {
    input.value = "";
  }
}

async function pasteImages(event: ClipboardEvent): Promise<void> {
  const files = Array.from(event.clipboardData?.items ?? [])
    .filter((item) => item.type.startsWith("image/"))
    .map((item) => item.getAsFile())
    .filter((file): file is File => file !== null);
  if (files.length === 0) return;
  event.preventDefault();
  try {
    await addImages(files);
    ElMessage.success(`已粘贴 ${files.length} 张图片到当前报告。`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "粘贴图片失败");
  }
}

async function addImages(files: File[]): Promise<void> {
  if (draft.value === null || uploadingImages.value) return;
  const accepted = files.filter((file) => ["image/png", "image/jpeg"].includes(file.type));
  if (accepted.length !== files.length) throw new Error("仅支持 PNG 或 JPEG 图片");
  if (allImageIds.value.length + accepted.length > 10) throw new Error("一份报告最多包含 10 张图片");
  if (accepted.some((file) => file.size > 10 * 1024 * 1024)) throw new Error("单张图片不能超过 10 MiB");
  uploadingImages.value = true;
  try {
    const ids: string[] = [];
    for (const file of accepted) {
      const uploaded = await businessApi.drafts.uploadImage(draft.value.id, file);
      ids.push(uploaded.fileId);
      draft.value.entityVersion = uploaded.entityVersion;
      draft.value.imageFileIds.push(uploaded.fileId);
    }
    pendingImageIds.value.push(...ids);
  } finally {
    uploadingImages.value = false;
  }
}

async function analyzeAllImages(): Promise<void> {
  if (draft.value === null || allImageIds.value.length === 0) {
    ElMessage.warning("请先在左侧排查分析中粘贴图片。");
    return;
  }
  await saveDraft(false);
  prompt.value = "逐张分析当前报告中的全部图片，结合系统事实和历史案例重写完整报告。";
  await send("IMAGE_ANALYSIS", pendingImageIds.value);
  ElMessage.success("全部图片已分析，左侧已回显最新完整报告。");
}

function imageUrl(id: string): string {
  return `/api/v1/files/${encodeURIComponent(id)}?inline=true`;
}

async function removeImage(id: string): Promise<void> {
  if (draft.value === null || sending.value) return;
  sending.value = true;
  try {
    draft.value = await businessApi.drafts.removeImage(
      draft.value.id,
      id,
      draft.value.entityVersion,
    );
    pendingImageIds.value = pendingImageIds.value.filter((value) => value !== id);
    ElMessage.success("图片已从当前报告移除。");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "移除图片失败");
  } finally {
    sending.value = false;
  }
}

async function moveImage(index: number, offset: -1 | 1): Promise<void> {
  if (draft.value === null || sending.value) return;
  const target = index + offset;
  if (target < 0 || target >= allImageIds.value.length) return;
  const ordered = [...allImageIds.value];
  [ordered[index], ordered[target]] = [ordered[target]!, ordered[index]!];
  sending.value = true;
  try {
    draft.value = await businessApi.drafts.reorderImages(
      draft.value.id,
      ordered,
      draft.value.entityVersion,
    );
    pendingImageIds.value = [];
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "调整图片顺序失败");
  } finally {
    sending.value = false;
  }
}

async function restore(version: DraftVersion): Promise<void> {
  if (draft.value === null) return;
  try {
    await standardConfirm(
      `将以 V${version.version} 的内容创建新的当前版本，现有历史不会删除。`,
      "恢复历史版本",
      {
        type: "warning",
        confirmButtonText: "确认恢复",
        cancelButtonText: "取消",
      },
    );
  } catch {
    return;
  }

  sending.value = true;
  try {
    draft.value = await businessApi.drafts.restore(draft.value.id, version.id);
    historyVisible.value = false;
    ElMessage.success("已基于历史内容创建新版本。");
  } finally {
    sending.value = false;
  }
}

function previewVersionRow(row: unknown): void {
  previewVersion.value = row as DraftVersion;
  previewVisible.value = true;
}

function restoreVersionRow(row: unknown): void {
  void restore(row as DraftVersion);
}

async function generate(): Promise<void> {
  if (draft.value === null) return;
  await saveDraft(false);
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
    const report = await businessApi.drafts.generate(draft.value.id);
    await saveGeneratedWord(report.id, report.wordFileName);
    await router.replace({
      name: "report-detail",
      params: { reportId: report.id },
      query: { from: "/reports/generate" },
    });
  } catch (error) {
    ElMessage.error(
      error instanceof Error ? error.message : "正式报告生成失败",
    );
  } finally {
    generating.value = false;
  }
}

async function saveGeneratedWord(reportId: string, fileName: string): Promise<void> {
  try {
    await triggerBrowserDownload(
      `/api/v1/reports/${encodeURIComponent(reportId)}/word`,
      fileName,
    );
  } catch {
    ElMessage.warning("正式报告已生成，Word 自动下载失败，可在报告详情页手动下载。");
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
        <small>报账点编码</small>
        <strong>{{ draft.billingPointCode ?? draft.billingPointId }}</strong>
      </div>
      <div>
        <small>报账点名称</small>
        <strong>{{ draft.billingPointName }}</strong>
      </div>
      <div>
        <small>所属区域</small>
        <strong>{{ draft.city?.name ?? "—" }}</strong>
      </div>
      <div>
        <small>账期</small>
        <strong>{{ draft.period }}</strong>
      </div>
      <div>
        <small>报告状态</small>
        <StatusTag :value="draft.status" />
      </div>
    </section>

    <div class="draft-workspace" :class="{ 'assistant-open': assistantVisible }">
      <article class="report-paper business-card" aria-label="可编辑报告正文">
        <h1
          v-if="headingBlock"
          contenteditable="true"
          spellcheck="false"
          @input="updateBlock(headingBlock, editableText($event))"
          @blur="saveDraft(false)"
        >
          {{ headingBlock.content }}
        </h1>

        <section v-for="(block, index) in bodyBlocks" :key="block.id">
          <h2>{{ chineseNumbers[index] ?? index + 1 }}、{{ block.title }}</h2>
          <p
            contenteditable="true"
            spellcheck="false"
            @input="updateBlock(block, editableText($event))"
            @blur="saveDraft(false)"
            @paste="block.type === 'ANALYSIS' ? pasteImages($event) : undefined"
          >
            {{ block.content }}
          </p>
          <div v-if="block.type === 'ANALYSIS' && allImageIds.length" class="evidence-grid">
            <figure v-for="(imageId, imageIndex) in allImageIds" :key="imageId">
              <img :src="imageUrl(imageId)" :alt="`稽核证据图片 ${imageIndex + 1}`" />
              <figcaption>
                <span>图片 IMG-{{ imageIndex + 1 }}</span>
                <span>
                  <ElButton link :disabled="imageIndex === 0" @click="moveImage(imageIndex, -1)">上移</ElButton>
                  <ElButton link :disabled="imageIndex === allImageIds.length - 1" @click="moveImage(imageIndex, 1)">下移</ElButton>
                  <ElButton link type="danger" @click="removeImage(imageId)">删除</ElButton>
                </span>
              </figcaption>
            </figure>
          </div>
        </section>
      </article>

      <aside v-if="assistantVisible" class="assistant-panel business-card">
        <header>
          <div>
            <h2>AI报告助手</h2>
            <small>分析图片后自动补充报告内容，人工确认后再生成正式报告。</small>
          </div>
          <ElButton
            link
            type="primary"
            :icon="Clock"
            @click="historyVisible = true"
          >
            历史版本
          </ElButton>
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
          <ElInput
            v-model="prompt"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            placeholder="输入问题或修改指令；Enter 发送，Shift+Enter 换行"
            :disabled="sending"
            @keydown.enter.exact.prevent="send('AUTO')"
          />
          <ElButton
            circle
            type="primary"
            :icon="Promotion"
            :loading="sending"
            :disabled="!prompt.trim()"
            aria-label="发送"
            @click="send('AUTO')"
          />
        </div>
      </aside>
    </div>

    <footer class="draft-actions">
      <ElButton :icon="ArrowLeft" @click="goBack">返回</ElButton>
      <ElButton :icon="Picture" :loading="uploadingImages" @click="chooseImage">
        添加图片
      </ElButton>
      <ElButton type="primary" plain :icon="Picture" :loading="sending" @click="analyzeAllImages">
        分析全部图片
      </ElButton>
      <ElButton
        type="primary"
        :loading="generating"
        :disabled="draft.status !== 'EDITING'"
        @click="generate"
      >
        确认报告并导出 Word
      </ElButton>
      <input
        ref="fileInput"
        class="sr-only"
        type="file"
        multiple
        accept="image/*"
        @change="imageSelected"
      />
    </footer>

    <ElDialog
      v-model="historyVisible"
      title="AI 报告历史版本"
      width="min(980px, 92vw)"
    >
      <ElAlert
        title="仅在修改报告正文时生成版本；普通问答不进入版本历史。"
        type="info"
        :closable="false"
        show-icon
      />
      <ElTable :data="[...draft.versions].reverse()" class="version-table">
        <ElTableColumn label="版本" width="130">
          <template #default="scope">
            <strong>V{{ scope.row.version }}</strong>
            <ElTag
              v-if="scope.row.id === draft.versions.at(-1)?.id"
              type="danger"
              size="small"
            >
              当前版本
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="createdAt" label="时间" width="190" />
        <ElTableColumn prop="reason" label="变更来源" width="130" />
        <ElTableColumn prop="summary" label="指令摘要" min-width="260" />
        <ElTableColumn label="操作" width="145">
          <template #default="scope">
            <div class="text-link-actions">
              <ElButton link type="primary" @click="previewVersionRow(scope.row)">
                预览
              </ElButton>
              <ElButton
                v-if="scope.row.id !== draft.versions.at(-1)?.id"
                link
                type="primary"
                @click="restoreVersionRow(scope.row)"
              >
                恢复
              </ElButton>
            </div>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElAlert
        title="恢复会基于所选版本创建一个新的当前版本，原有历史版本不会删除。"
        type="info"
        :closable="false"
      />
      <template #footer>
        <ElButton @click="historyVisible = false">关闭</ElButton>
      </template>
    </ElDialog>

    <ElDrawer v-model="previewVisible" title="版本预览" size="520px">
      <template v-if="previewVersion">
        <h3>V{{ previewVersion.version }} · {{ previewVersion.summary }}</h3>
        <section v-for="block in previewVersion.blocks" :key="block.id">
          <h4>{{ block.title }}</h4>
          <p>{{ block.content }}</p>
        </section>
      </template>
    </ElDrawer>
  </template>
</template>

<style scoped>
.draft-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 160px), 1fr));
  gap: 18px;
  padding: 16px 24px;
  margin-bottom: 16px;
}

.draft-summary > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
}

.draft-summary small {
  color: #7d8ca1;
  font-size: 12px;
  font-weight: 600;
}

.draft-summary strong {
  overflow: hidden;
  color: #1f2d3d;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.draft-workspace {
  display: grid;
  min-height: calc(100vh - 212px);
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
  padding-bottom: 72px;
}

.draft-workspace.assistant-open {
  grid-template-columns: minmax(0, 1fr) minmax(280px, 430px);
}

.report-paper {
  position: relative;
  padding: clamp(18px, 3vw, 28px) clamp(16px, 4vw, 36px) 42px;
  background: #fff;
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

.report-paper p {
  min-height: 84px;
  padding: 6px 8px;
  margin: 0;
  color: #1f2d3d;
  line-height: 2;
  white-space: pre-wrap;
  border: 1px solid transparent;
  border-radius: 4px;
}

.report-paper h1[contenteditable="true"],
.report-paper p[contenteditable="true"] {
  cursor: text;
  outline: none;
}

.report-paper h1[contenteditable="true"]:focus,
.report-paper p[contenteditable="true"]:focus {
  background: #fffdfd;
  border-color: #ffb8c1;
  box-shadow: 0 0 0 3px rgb(237 36 55 / 8%);
}

.evidence-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 14px;
  margin-top: 14px;
}

.evidence-grid figure {
  margin: 0;
  overflow: hidden;
  background: #f6f8fb;
  border: 1px solid #dfe5ec;
  border-radius: 8px;
}

.evidence-grid img {
  display: block;
  width: 100%;
  max-height: 420px;
  object-fit: contain;
  background: #eef2f7;
}

.evidence-grid figcaption {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  color: #52657a;
  font-size: 12px;
}

.assistant-panel {
  display: grid;
  min-height: 0;
  grid-template-rows: auto 1fr auto;
  overflow: hidden;
  background: #fff;
}

.assistant-panel > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 22px 18px 16px;
  border-bottom: 1px solid #edf1f5;
}

.assistant-panel h2 {
  margin: 0 0 6px;
  color: #1f2d3d;
  font-size: 20px;
}

.assistant-panel header small {
  color: #7d8ca1;
  line-height: 1.7;
}

.chat-list {
  display: flex;
  max-height: calc(100vh - 390px);
  flex-direction: column;
  gap: 12px;
  padding: 18px;
  overflow-y: auto;
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
}

.chat-message small {
  color: #7d8ca1;
}

.prompt-box {
  position: relative;
  padding: 16px;
  border-top: 1px solid #edf1f5;
}

.prompt-box .el-button {
  position: absolute;
  right: 26px;
  bottom: 28px;
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

.version-table {
  margin: 14px 0;
}

.version-table strong {
  margin-right: 8px;
}

@media (width <= 1280px) {
  .draft-summary,
  .draft-workspace,
  .draft-workspace.assistant-open {
    grid-template-columns: 1fr;
  }
}

@media (width <= 640px) {
  .draft-actions {
    right: 12px;
    left: 12px;
    align-items: stretch;
    flex-direction: column;
  }

  .draft-actions .el-button {
    width: 100%;
  }
}
</style>
