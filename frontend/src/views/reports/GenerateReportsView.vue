<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ArrowLeft,
  Clock,
  Picture,
  Refresh,
  Select,
} from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "element-plus";

import { businessApi } from "@/api/business-api";
import PageState from "@/components/PageState.vue";
import { useSessionStore } from "@/stores/session";
import type {
  BillingPointDetail,
  DraftBlock,
  DraftVersion,
  PageResult,
  ReportDraft,
} from "@/types/business";

type Summary = BillingPointDetail["summary"];

const router = useRouter();
const route = useRoute();
const session = useSessionStore();

const loading = ref(false);
const opening = ref(false);
const draftLoading = ref(false);
const saving = ref(false);
const restoring = ref(false);
const errorMessage = ref("");
const pageData = ref<PageResult<Summary> | null>(null);
const currentDraft = ref<ReportDraft | null>(null);
const selectedId = ref("");
const versionDialogVisible = ref(false);
const previewVersion = ref<DraftVersion | null>(null);
const filters = reactive({ period: "2026-06", page: 1, size: 10 });

const candidates = computed(() => pageData.value?.items ?? []);
const selected = computed(
  () => candidates.value.find((item) => item.id === selectedId.value) ?? null,
);
const versions = computed(() => [...(currentDraft.value?.versions ?? [])].reverse());
const taskCountText = computed(() => `共 ${pageData.value?.totalElements ?? 0} 项`);

const auditResultText = computed(() => {
  if (selected.value === null) return "暂无";
  const ratio = selected.value.deviationRate;
  return `${selected.value.overLimitType ?? "多项超标"} · ${ratio ?? "待复核"}`;
});

const periodRangeText = computed(() => {
  if (selected.value?.periodStart && selected.value.periodEnd) {
    return `${selected.value.periodStart} 至 ${selected.value.periodEnd}`;
  }
  return selected.value?.period ?? filters.period;
});

const headingBlock = computed(
  () =>
    currentDraft.value?.blocks.find((block) => block.type === "HEADING") ??
    null,
);
const reportBlocks = computed(
  () =>
    currentDraft.value?.blocks.filter((block) => block.type !== "HEADING") ??
    [],
);

function editableText(event: Event): string {
  return (event.target as HTMLElement).innerText.trim();
}

function updateBlock(block: DraftBlock, content: string): void {
  if (currentDraft.value === null) return;
  const target = currentDraft.value.blocks.find((item) => item.id === block.id);
  if (target !== undefined) target.content = content;
}

function sectionPrefix(index: number): string {
  return ["一", "二", "三", "四"][index] ?? String(index + 1);
}

function asDraftVersion(row: unknown): DraftVersion {
  return row as DraftVersion;
}

async function saveDraft(): Promise<void> {
  if (currentDraft.value === null || saving.value) return;
  saving.value = true;
  try {
    currentDraft.value = await businessApi.drafts.save(
      currentDraft.value.id,
      currentDraft.value,
    );
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
    const result = await businessApi.billingPoints.list({
      cityCode: session.currentUser?.city?.code ?? "",
      period: filters.period,
      keyword: "",
      auditStatus: "OVER_LIMIT",
      page: filters.page,
      size: filters.size,
    });
    const items = result.items.filter((item) => item.reportStatus !== "FINAL");
    pageData.value = {
      ...result,
      items,
      totalElements: items.length,
      totalPages: Math.max(1, Math.ceil(items.length / filters.size)),
    };
    selectedId.value = items[0]?.id ?? "";
    await loadCurrentDraft();
  } catch (error) {
    errorMessage.value =
      error instanceof Error ? error.message : "待生成报告任务加载失败";
  } finally {
    loading.value = false;
  }
}

async function loadCurrentDraft(): Promise<void> {
  if (selected.value === null) {
    currentDraft.value = null;
    return;
  }
  draftLoading.value = true;
  try {
    currentDraft.value = selected.value.draftId
      ? ((await businessApi.drafts.get(selected.value.draftId)) ?? null)
      : await businessApi.drafts.createOrResume(selected.value.id);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "报告工作稿加载失败");
    currentDraft.value = null;
  } finally {
    draftLoading.value = false;
  }
}

async function openDraft(action: "edit" | "image" | "generate" = "edit"): Promise<void> {
  if ((selected.value === null && currentDraft.value === null) || opening.value) return;
  opening.value = true;
  try {
    await saveDraft();
    const draft =
      currentDraft.value ??
      (selected.value?.draftId
        ? await businessApi.drafts.get(selected.value.draftId)
        : selected.value
          ? await businessApi.drafts.createOrResume(selected.value.id)
          : undefined);
    if (draft === undefined) {
      ElMessage.warning("工作稿不存在或当前账号无权访问。");
      return;
    }
    await router.push({
      name: "report-draft",
      params: { draftId: draft.id },
      query: { from: route.fullPath, action },
    });
  } finally {
    opening.value = false;
  }
}

function openVersions(): void {
  previewVersion.value = versions.value[0] ?? null;
  versionDialogVisible.value = true;
}

async function restoreVersion(version: DraftVersion): Promise<void> {
  if (currentDraft.value === null || restoring.value) return;
  try {
    await ElMessageBox.confirm(
      `确认恢复 V${version.version}？系统会基于该版本创建新的当前版本，原历史版本不会删除。`,
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
  restoring.value = true;
  try {
    currentDraft.value = await businessApi.drafts.restore(
      currentDraft.value.id,
      version.id,
    );
    previewVersion.value = versions.value[0] ?? null;
    ElMessage.success("历史版本已恢复");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "历史版本恢复失败");
  } finally {
    restoring.value = false;
  }
}

watch(selectedId, () => {
  void loadCurrentDraft();
});

onMounted(load);
</script>

<template>
  <PageState v-if="!pageData && loading" kind="loading" />
  <PageState
    v-else-if="errorMessage"
    kind="error"
    title="任务加载失败"
    :description="errorMessage"
    @retry="load"
  />

  <template v-else>
    <template v-if="selected">
      <section class="report-head business-card">
        <div>
          <small>报账点编码</small>
          <strong>{{ selected.code }}</strong>
        </div>
        <div class="point-selector">
          <small>报账点名称</small>
          <ElSelect v-model="selectedId" filterable>
            <ElOption
              v-for="item in candidates"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </ElSelect>
        </div>
        <div>
          <small>所属区域</small>
          <strong>{{ selected.city.name }} / {{ selected.district ?? "—" }}</strong>
        </div>
        <div>
          <small>账期</small>
          <strong>{{ periodRangeText }}</strong>
        </div>
        <div>
          <small>稽核结果</small>
          <strong class="danger-text">{{ auditResultText }}</strong>
        </div>
      </section>

      <div class="report-workspace">
        <article v-loading="draftLoading" class="report-paper business-card">
          <h1
            v-if="headingBlock"
            contenteditable="true"
            spellcheck="false"
            @input="updateBlock(headingBlock, editableText($event))"
            @blur="saveDraft"
          >
            {{ headingBlock.content }}
          </h1>
          <section v-for="(block, index) in reportBlocks" :key="block.id">
            <h2>{{ sectionPrefix(index) }}、{{ block.title }}</h2>
            <p
              contenteditable="true"
              spellcheck="false"
              @input="updateBlock(block, editableText($event))"
              @blur="saveDraft"
            >
              {{ block.content }}
            </p>
            <div v-if="block.type === 'IMAGE'" class="evidence-grid">
              <div>
                <span>{{ block.imageName ?? "现场图片分析结果" }}</span>
              </div>
            </div>
          </section>
        </article>

        <aside class="assistant-card business-card">
          <header>
            <div>
              <h2>AI报告助手</h2>
              <p>支持提问与修改报告</p>
            </div>
            <ElButton link type="primary" :icon="Clock" @click="openVersions">
              历史版本
            </ElButton>
          </header>

          <div class="assistant-flow">
            <ElEmpty
              v-if="(currentDraft?.messages ?? []).length === 0"
              description="点击“分析图片”后，AI 分析记录会在这里显示"
            />
            <div
              v-for="message in currentDraft?.messages ?? []"
              v-else
              :key="message.id"
              class="chat"
              :class="message.role === 'USER' ? 'user' : 'ai'"
            >
              <span>{{ message.role === "USER" ? "您" : "AI" }}</span>
              <p>
                {{ message.content }}
                <small>{{ message.createdAt.slice(11, 16) }}</small>
              </p>
            </div>
          </div>

          <div class="assistant-input">
            <ElInput
              type="textarea"
              :rows="4"
              placeholder="请输入问题或修改指令"
              disabled
            />
            <ElButton circle type="primary" :icon="Select" @click="openDraft('edit')" />
          </div>
        </aside>
      </div>

      <footer class="report-actions">
        <ElButton :icon="ArrowLeft" @click="router.push('/dashboard')">返回</ElButton>
        <ElButton :icon="Picture" :loading="opening" @click="openDraft('image')">
          分析图片
        </ElButton>
        <ElButton type="primary" :loading="opening" @click="openDraft('generate')">
          生成正式报告并导出Word
        </ElButton>
      </footer>
    </template>

    <section v-else class="empty-card business-card">
      <ElEmpty description="当前账期暂无待生成报告">
        <ElButton type="primary" :icon="Refresh" @click="load">重新加载</ElButton>
      </ElEmpty>
      <small>{{ taskCountText }}</small>
    </section>
  </template>

  <ElDialog
    v-model="versionDialogVisible"
    title="AI 报告历史版本"
    width="760px"
    class="version-dialog"
    :close-on-click-modal="false"
  >
    <div class="version-layout">
      <ElTable :data="versions" height="300">
        <ElTableColumn label="版本" width="90">
          <template #default="scope">V{{ scope.row.version }}</template>
        </ElTableColumn>
        <ElTableColumn prop="summary" label="变更类型" width="130" />
        <ElTableColumn label="创建时间" min-width="170">
          <template #default="scope">{{ scope.row.createdAt.replace("T", " ").slice(0, 16) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="150">
          <template #default="scope">
            <div class="text-link-actions">
              <ElButton link type="primary" @click="previewVersion = asDraftVersion(scope.row)">
                预览
              </ElButton>
              <ElButton
                link
                type="primary"
                :loading="restoring"
                @click="restoreVersion(asDraftVersion(scope.row))"
              >
                恢复
              </ElButton>
            </div>
          </template>
        </ElTableColumn>
      </ElTable>

      <section class="version-preview">
        <h3>
          {{ previewVersion ? `V${previewVersion.version} 预览` : "暂无历史版本" }}
        </h3>
        <template v-if="previewVersion">
          <article v-for="block in previewVersion.blocks" :key="block.id">
            <strong>{{ block.title }}</strong>
            <p>{{ block.content }}</p>
          </article>
        </template>
      </section>
    </div>
  </ElDialog>
</template>

<style scoped>
.report-head {
  display: grid;
  grid-template-columns: 180px minmax(260px, 1.25fr) 210px 245px 180px;
  gap: 18px;
  align-items: center;
  padding: 14px 22px;
  margin-bottom: 14px;
}

.report-head > div {
  display: grid;
  min-width: 0;
  gap: 6px;
}

.report-head small {
  color: #7d8ca1;
  font-weight: 600;
}

.report-head strong {
  color: #1f2d3d;
  font-size: 15px;
}

.point-selector :deep(.el-select) {
  width: 100%;
}

.danger-text {
  color: #ed2437 !important;
}

.report-workspace {
  display: grid;
  min-height: calc(100vh - 276px);
  grid-template-columns: minmax(0, 1fr) 430px;
  gap: 14px;
  padding-bottom: 72px;
}

.report-paper {
  padding: 34px 42px 46px;
}

.report-paper h1 {
  min-height: 38px;
  margin: 0 0 28px;
  color: #101827;
  font-size: 22px;
  line-height: 1.6;
  text-align: center;
}

.report-paper h2 {
  margin: 22px 0 12px;
  color: #101827;
  font-size: 16px;
}

.report-paper p {
  min-height: 74px;
  padding: 4px 6px;
  margin: 0;
  color: #1f2d3d;
  line-height: 1.9;
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
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
  margin: 20px 0;
}

.evidence-grid div {
  display: flex;
  min-height: 112px;
  align-items: flex-end;
  padding: 10px;
  overflow: hidden;
  color: #fff;
  background:
    linear-gradient(180deg, rgb(15 23 42 / 8%), rgb(15 23 42 / 72%)),
    linear-gradient(135deg, #dbe4ef, #8ea0b6);
  border-radius: 6px;
}

.assistant-card {
  display: grid;
  grid-template-rows: auto 1fr auto;
  overflow: hidden;
}

.assistant-card header {
  display: flex;
  justify-content: space-between;
  padding: 20px 18px 14px;
  border-bottom: 1px solid #edf1f5;
}

.assistant-card h2 {
  margin: 0;
  color: #1f2d3d;
  font-size: 18px;
}

.assistant-card p {
  margin: 0;
}

.assistant-card header p {
  color: #7d8ca1;
}

.assistant-flow {
  display: grid;
  align-content: start;
  gap: 16px;
  padding: 18px;
}

.chat {
  display: flex;
  gap: 12px;
}

.chat.user {
  justify-content: flex-end;
}

.chat.user span {
  order: 2;
}

.chat span {
  display: grid;
  width: 28px;
  height: 28px;
  flex: none;
  place-items: center;
  color: #fff;
  background: #314760;
  border-radius: 50%;
  font-size: 12px;
}

.chat.ai span {
  color: #314760;
  background: #edf3fb;
}

.chat p {
  max-width: 320px;
  padding: 12px 14px;
  color: #1f2d3d;
  background: #eaf3ff;
  border-radius: 8px;
  line-height: 1.8;
}

.chat.ai p {
  background: #fff1f2;
}

.chat small {
  display: block;
  margin-top: 4px;
  color: #7d8ca1;
}

.assistant-input {
  position: relative;
  padding: 16px;
  border-top: 1px solid #edf1f5;
}

.assistant-input .el-button {
  position: absolute;
  right: 26px;
  bottom: 28px;
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

.empty-card {
  display: grid;
  min-height: 520px;
  place-items: center;
}

.empty-card small {
  color: #7d8ca1;
}

.version-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(260px, 0.8fr);
  gap: 16px;
}

.version-preview {
  min-height: 300px;
  padding: 14px;
  overflow: auto;
  background: #f8fafd;
  border: 1px solid #edf1f5;
  border-radius: 8px;
}

.version-preview h3 {
  margin: 0 0 12px;
}

.version-preview article {
  padding-top: 10px;
  border-top: 1px solid #edf1f5;
}

.version-preview article:first-of-type {
  border-top: 0;
}

.version-preview p {
  color: #4d5d73;
  line-height: 1.8;
  white-space: pre-wrap;
}

@media (width <= 1280px) {
  .report-head,
  .report-workspace,
  .version-layout {
    grid-template-columns: 1fr;
  }
}
</style>
