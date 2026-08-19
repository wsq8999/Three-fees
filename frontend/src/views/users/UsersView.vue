<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { Key, Plus, Refresh, Search } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";

import { businessApi } from "@/api/business-api";
import PageState from "@/components/PageState.vue";
import type { BusinessCity, ManagedUser, PageResult } from "@/types/business";
import { standardConfirm } from "@/utils/message-box";

const loading = ref(false);
const saving = ref(false);
const errorMessage = ref("");
const pageData = ref<PageResult<ManagedUser> | null>(null);
const cities = ref<BusinessCity[]>([]);
const createVisible = ref(false);
const editVisible = ref(false);
const passwordVisible = ref(false);
const editingUser = ref<ManagedUser | null>(null);
const passwordUser = ref<ManagedUser | null>(null);
const formError = ref("");
const passwordError = ref("");

/*
 * 查询区当前正在编辑的条件。
 * 用户在输入框/下拉框中修改这些值时，不立即影响列表。
 */
const filters = reactive({
  keyword: "",
  cityCode: "",
  enabled: "",
  page: 1,
  size: 10,
});

/*
 * 已经点击“查询”后真正生效的条件。
 * 列表只读取这里，不直接读取上面的 filters。
 */
const appliedFilters = reactive({
  keyword: "",
  cityCode: "",
  enabled: "",
});

const createForm = reactive({
  username: "",
  displayName: "",
  cityCode: "",
  enabled: true,
  initialPassword: "",
  confirmPassword: "",
});

const editForm = reactive({
  displayName: "",
  cityCode: "",
  enabled: true,
});

const passwordForm = reactive({
  newPassword: "",
  confirmPassword: "",
});

const filteredItems = computed(() => {
  const keyword = appliedFilters.keyword.trim().toLowerCase();

  return (pageData.value?.items ?? []).filter((user) => {
    const matchedKeyword =
      keyword.length === 0 ||
      [user.username, user.displayName].some((value) =>
        value.toLowerCase().includes(keyword),
      );

    const matchedCity =
      appliedFilters.cityCode.length === 0 ||
      user.city?.code === appliedFilters.cityCode;

    const matchedStatus =
      appliedFilters.enabled.length === 0 ||
      String(user.enabled) === appliedFilters.enabled;

    return matchedKeyword && matchedCity && matchedStatus;
  });
});

function roleLabel(user: ManagedUser): string {
  return user.roles.includes("SUPER_ADMIN") ? "超级管理员" : "市级业务用户";
}

function dataScope(user: ManagedUser): string {
  return user.city?.name ?? "江苏省全部";
}

function formatTime(value: string): string {
  return value.replace("T", " ").slice(0, 16);
}

async function load(): Promise<void> {
  loading.value = true;
  errorMessage.value = "";

  try {
    pageData.value = await businessApi.users.list(filters.page, filters.size);
  } catch (error) {
    errorMessage.value =
      error instanceof Error ? error.message : "用户列表加载失败";
  } finally {
    loading.value = false;
  }
}

/*
 * 只有点击“查询”按钮时，才把当前查询区条件正式应用到列表。
 * 单独输入文字、选择城市、选择账号状态都不会立即筛选。
 */
async function searchUsers(): Promise<void> {
  Object.assign(appliedFilters, {
    keyword: filters.keyword,
    cityCode: filters.cityCode,
    enabled: filters.enabled,
  });

  filters.page = 1;
  await load();
}

function resetFilters(): void {
  Object.assign(filters, {
    keyword: "",
    cityCode: "",
    enabled: "",
    page: 1,
  });

  Object.assign(appliedFilters, {
    keyword: "",
    cityCode: "",
    enabled: "",
  });

  void load();
}

function changePage(page: number): void {
  filters.page = page;
  void load();
}

function openCreate(): void {
  Object.assign(createForm, {
    username: "",
    displayName: "",
    cityCode: cities.value[0]?.code ?? "",
    enabled: true,
    initialPassword: "",
    confirmPassword: "",
  });

  formError.value = "";
  createVisible.value = true;
}

async function createUser(): Promise<void> {
  formError.value = "";

  if (
    createForm.username.trim().length === 0 ||
    createForm.displayName.trim().length === 0 ||
    createForm.cityCode.length === 0 ||
    createForm.initialPassword.length === 0 ||
    createForm.confirmPassword.length === 0
  ) {
    formError.value = "请完整填写用户名、姓名和绑定城市。";
    return;
  }

  if (createForm.initialPassword !== createForm.confirmPassword) {
    formError.value = "两次输入的初始密码不一致。";
    return;
  }

  if (createForm.initialPassword.length < 6) {
    formError.value = "初始密码至少 6 位。";
    return;
  }

  if (!/^[a-zA-Z][a-zA-Z0-9_]{3,31}$/.test(createForm.username)) {
    formError.value =
      "用户名需以字母开头，只能包含字母、数字和下划线，长度 4–32 位。";
    return;
  }

  saving.value = true;

  try {
    await businessApi.users.create({
      username: createForm.username.trim(),
      displayName: createForm.displayName.trim(),
      cityCode: createForm.cityCode,
      enabled: createForm.enabled,
      initialPassword: createForm.initialPassword,
      confirmPassword: createForm.confirmPassword,
    });

    createVisible.value = false;
    await load();
    ElMessage.success("用户已创建，可直接使用初始密码登录");
  } catch (error) {
    formError.value = error instanceof Error ? error.message : "用户创建失败";
  } finally {
    saving.value = false;
  }
}

function openEdit(user: ManagedUser): void {
  editingUser.value = user;
  editForm.displayName = user.displayName;
  editForm.cityCode = user.city?.code ?? cities.value[0]?.code ?? "";
  editForm.enabled = user.enabled;
  formError.value = "";
  editVisible.value = true;
}

async function saveEdit(): Promise<void> {
  if (editingUser.value === null) return;

  if (editForm.displayName.trim().length === 0) {
    formError.value = "姓名不能为空。";
    return;
  }

  saving.value = true;

  try {
    await businessApi.users.update(editingUser.value.id, {
      displayName: editForm.displayName.trim(),
      cityCode: editForm.cityCode,
      enabled: editForm.enabled,
      version: editingUser.value.version,
    });

    editVisible.value = false;
    await load();
    ElMessage.success("用户信息已更新");
  } catch (error) {
    formError.value = error instanceof Error ? error.message : "保存失败";
  } finally {
    saving.value = false;
  }
}

function openPassword(user: ManagedUser): void {
  passwordUser.value = user;
  Object.assign(passwordForm, {
    newPassword: "",
    confirmPassword: "",
  });

  passwordError.value = "";
  passwordVisible.value = true;
}

async function resetPassword(): Promise<void> {
  if (passwordUser.value === null) return;

  passwordError.value = "";

  if (passwordForm.newPassword.length < 6) {
    passwordError.value = "新密码至少 6 位。";
    return;
  }

  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    passwordError.value = "两次输入的新密码不一致。";
    return;
  }

  saving.value = true;

  try {
    await businessApi.users.resetPassword(
      passwordUser.value.id,
      passwordForm.newPassword,
      passwordForm.confirmPassword,
    );

    passwordVisible.value = false;
    await load();
    ElMessage.success("密码已更新，新密码立即生效");
  } catch (error) {
    passwordError.value =
      error instanceof Error ? error.message : "密码修改失败";
  } finally {
    saving.value = false;
  }
}

async function toggleEnabled(user: ManagedUser): Promise<void> {
  const target = !user.enabled;

  try {
    await standardConfirm(
      target
        ? `确认启用 ${user.displayName}？`
        : `停用后 ${user.displayName} 将不能登录。确认停用？`,
      target ? "启用账号" : "停用账号",
      {
        type: target ? "info" : "warning",
        confirmButtonText: target ? "确认启用" : "确认停用",
        cancelButtonText: "取消",
      },
    );
  } catch {
    return;
  }

  try {
    await businessApi.users.update(user.id, {
      displayName: user.displayName,
      cityCode: user.city?.code ?? "",
      enabled: target,
      version: user.version,
    });

    await load();
    ElMessage.success(target ? "账号已启用" : "账号已停用");
  } catch (error) {
    ElMessage.error(
      error instanceof Error ? error.message : "账号状态修改失败",
    );
  }
}

function asManagedUser(row: unknown): ManagedUser {
  return row as ManagedUser;
}

onMounted(async () => {
  cities.value = await businessApi.cities.list();
  await load();
});
</script>

<template>
  <section class="user-filter user-query-bar-final business-card">
    <div class="user-filter-fields user-query-fields-final">
      <label>
        <span>用户名</span>
        <ElInput
          v-model="filters.keyword"
          placeholder="请输入关键词"
          clearable
        />
      </label>

      <label>
        <span>城市</span>
        <ElSelect
          v-model="filters.cityCode"
          placeholder="全部城市"
          clearable
        >
          <ElOption
            v-for="city in cities"
            :key="city.code"
            :label="city.name"
            :value="city.code"
          />
        </ElSelect>
      </label>

      <label>
        <span>账号状态</span>
        <ElSelect
          v-model="filters.enabled"
          placeholder="全部"
          clearable
        >
          <ElOption label="启用" value="true" />
          <ElOption label="停用" value="false" />
        </ElSelect>
      </label>
    </div>

    <div class="filter-actions user-query-actions-final">
      <ElButton
        type="primary"
        :icon="Search"
        :loading="loading"
        @click="searchUsers"
      >
        查询
      </ElButton>

      <ElButton
        :icon="Refresh"
        @click="resetFilters"
      >
        重置
      </ElButton>
    </div>
  </section>

  <div class="business-toolbar">
    <ElButton
      type="primary"
      :icon="Plus"
      @click="openCreate"
    >
      新增用户
    </ElButton>

    <span>共 {{ filteredItems.length }} 用户</span>
  </div>

  <PageState
    v-if="!pageData && loading"
    kind="loading"
  />

  <PageState
    v-else-if="errorMessage"
    kind="error"
    title="用户列表加载失败"
    :description="errorMessage"
    @retry="load"
  />

  <section
    v-else
    class="user-table business-card"
  >
    <ElTable
      v-loading="loading"
      :data="filteredItems"
    >
      <ElTableColumn
        prop="username"
        label="用户名"
        width="155"
      />

      <ElTableColumn
        prop="displayName"
        label="姓名"
        min-width="180"
      />

      <ElTableColumn
        label="角色"
        width="155"
      >
        <template #default="scope">
          {{ roleLabel(asManagedUser(scope.row)) }}
        </template>
      </ElTableColumn>

      <ElTableColumn
        label="城市"
        min-width="155"
      >
        <template #default="scope">
          {{ dataScope(asManagedUser(scope.row)) }}
        </template>
      </ElTableColumn>

      <ElTableColumn
        label="账号状态"
        width="110"
      >
        <template #default="scope">
          <ElTag :type="scope.row.enabled ? 'success' : 'info'">
            {{ scope.row.enabled ? "启用" : "停用" }}
          </ElTag>
        </template>
      </ElTableColumn>

      <ElTableColumn
        label="最后登录时间"
        width="170"
      >
        <template #default="scope">
          {{ formatTime(asManagedUser(scope.row).updatedAt) }}
        </template>
      </ElTableColumn>

      <ElTableColumn
        label="操作"
        min-width="230"
        fixed="right"
      >
        <template #default="scope">
          <ElButton
            link
            type="danger"
            @click="openEdit(asManagedUser(scope.row))"
          >
            编辑
          </ElButton>

          <ElButton
            link
            type="danger"
            :icon="Key"
            @click="openPassword(asManagedUser(scope.row))"
          >
            重置密码
          </ElButton>

          <ElButton
            v-if="!scope.row.roles.includes('SUPER_ADMIN')"
            link
            type="danger"
            @click="toggleEnabled(asManagedUser(scope.row))"
          >
            {{ scope.row.enabled ? "停用" : "启用" }}
          </ElButton>
        </template>
      </ElTableColumn>

      <template #empty>
        <ElEmpty description="没有符合条件的用户" />
      </template>
    </ElTable>

    <footer>
      <span>
        已显示 {{ filteredItems.length }} 条，共
        {{ pageData?.totalElements ?? 0 }} 条
      </span>

      <ElPagination
        background
        layout="prev, pager, next"
        :current-page="filters.page"
        :page-size="filters.size"
        :total="pageData?.totalElements ?? 0"
        @current-change="changePage"
      />
    </footer>
  </section>

  <ElDialog
    v-model="createVisible"
    title="新增用户"
    width="690px"
    class="prototype-dialog centered-dialog"
    append-to-body
    align-center
    :close-on-click-modal="false"
  >
    <ElForm label-position="top">
      <div class="dialog-grid">
        <ElFormItem label="用户名 *">
          <ElInput
            v-model="createForm.username"
            maxlength="32"
            placeholder="请输入登录用户名"
          />
        </ElFormItem>

        <ElFormItem label="姓名 *">
          <ElInput
            v-model="createForm.displayName"
            maxlength="50"
            placeholder="请输入姓名"
          />
        </ElFormItem>

        <ElFormItem label="角色 *">
          <ElInput
            model-value="市级业务用户"
            disabled
          />
        </ElFormItem>

        <ElFormItem label="绑定城市 *">
          <ElSelect
            v-model="createForm.cityCode"
            placeholder="请选择城市"
          >
            <ElOption
              v-for="city in cities"
              :key="city.code"
              :label="city.name"
              :value="city.code"
            />
          </ElSelect>
        </ElFormItem>

        <ElFormItem label="初始密码 *">
          <ElInput
            v-model="createForm.initialPassword"
            type="password"
            show-password
          />
        </ElFormItem>

        <ElFormItem label="确认初始密码 *">
          <ElInput
            v-model="createForm.confirmPassword"
            type="password"
            show-password
          />
        </ElFormItem>

        <ElFormItem label="账号状态 *">
          <ElSelect v-model="createForm.enabled">
            <ElOption
              label="启用"
              :value="true"
            />
            <ElOption
              label="停用"
              :value="false"
            />
          </ElSelect>
        </ElFormItem>
      </div>

      <ElAlert
        v-if="formError"
        :title="formError"
        type="error"
        :closable="false"
        show-icon
      />
    </ElForm>

    <template #footer>
      <ElButton @click="createVisible = false">
        取消
      </ElButton>

      <ElButton
        type="primary"
        :loading="saving"
        @click="createUser"
      >
        保存并生成初始密码
      </ElButton>
    </template>
  </ElDialog>

  <ElDialog
    v-model="editVisible"
    title="编辑用户"
    width="520px"
    class="prototype-dialog centered-dialog"
    append-to-body
    align-center
    :close-on-click-modal="false"
  >
    <ElForm label-position="top">
      <ElFormItem label="用户名">
        <ElInput
          :model-value="editingUser?.username"
          disabled
        />
      </ElFormItem>

      <ElFormItem label="姓名 *">
        <ElInput
          v-model="editForm.displayName"
          maxlength="50"
        />
      </ElFormItem>

      <ElFormItem label="城市">
        <ElInput
          :model-value="editingUser ? dataScope(editingUser) : ''"
          disabled
        />
      </ElFormItem>

      <ElAlert
        v-if="formError"
        :title="formError"
        type="error"
        :closable="false"
        show-icon
      />
    </ElForm>

    <template #footer>
      <ElButton @click="editVisible = false">
        取消
      </ElButton>

      <ElButton
        type="primary"
        :loading="saving"
        @click="saveEdit"
      >
        保存
      </ElButton>
    </template>
  </ElDialog>

  <ElDialog
    v-model="passwordVisible"
    title="修改密码"
    width="480px"
    class="prototype-dialog centered-dialog"
    append-to-body
    align-center
    :close-on-click-modal="false"
  >
    <ElForm label-position="top">
      <ElFormItem label="姓名">
        <ElInput
          :model-value="passwordUser?.displayName ?? ''"
          disabled
        />
      </ElFormItem>

      <ElFormItem label="新密码 *">
        <ElInput
          v-model="passwordForm.newPassword"
          type="password"
          show-password
        />
      </ElFormItem>

      <ElFormItem label="确认新密码 *">
        <ElInput
          v-model="passwordForm.confirmPassword"
          type="password"
          show-password
        />
      </ElFormItem>

      <ElAlert
        v-if="passwordError"
        :title="passwordError"
        type="error"
        :closable="false"
        show-icon
      />
    </ElForm>

    <template #footer>
      <ElButton @click="passwordVisible = false">
        取消
      </ElButton>

      <ElButton
        type="primary"
        :loading="saving"
        @click="resetPassword"
      >
        确认
      </ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
/*
 * 用户管理查询区：
 * 与历史报告查询区保持同一种布局逻辑。
 * 大屏、小屏都保持一行，不改成两行或单列。
 */
.user-filter {
  display: flex;

  width: 100%;

  gap: 12px;
  align-items: flex-end;
  justify-content: space-between;

  padding: 14px 16px;
  margin-bottom: 14px;

  box-sizing: border-box;

  flex-wrap: nowrap;
}

/*
 * 三个查询字段均匀分配剩余空间，并铺满查询卡片。
 */
.user-filter-fields {
  display: grid;

  min-width: 0;
  flex: 1 1 auto;

  grid-template-columns: repeat(3, minmax(0, 1fr));

  gap: 10px;
  align-items: end;
}

.user-filter-fields label {
  display: grid;

  min-width: 0;

  gap: 6px;

  color: #1f2d3d;
  font-size: 14px;
  font-weight: 600;
}

.user-filter-fields :deep(.el-input),
.user-filter-fields :deep(.el-select) {
  width: 100% !important;
  min-width: 0 !important;
}

.user-filter-fields :deep(.el-input__wrapper),
.user-filter-fields :deep(.el-select__wrapper) {
  width: 100%;
  min-width: 0;

  box-sizing: border-box;
}

/*
 * 查询、重置始终在最右侧，并保持同一行。
 */
.filter-actions {
  display: flex;

  min-width: max-content;
  flex: 0 0 auto;
  flex-wrap: nowrap;

  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  align-self: flex-end;

  margin-left: auto;

  white-space: nowrap;
}

.filter-actions .el-button {
  min-width: 68px;
  flex: 0 0 auto;

  margin-left: 0;
  padding-right: 10px;
  padding-left: 10px;
}

.user-table {
  overflow-x: auto;
  overflow-y: hidden;
}

.user-table > .el-alert {
  margin: 12px 16px 16px;
  background: #eef5ff;
}

.user-table footer {
  display: flex;

  min-height: 58px;

  align-items: center;
  justify-content: space-between;

  padding: 10px 16px;

  color: #607089;

  border-top: 1px solid #edf1f5;
}

.dialog-grid {
  display: grid;

  grid-template-columns: 1fr 1fr;

  gap: 0 16px;
}

.dialog-grid :deep(.el-select) {
  width: 100%;
}

.permission-note {
  padding: 16px 18px;
  margin: 8px 0 14px;

  color: #4d5d73;

  background: #f6f8fc;

  border-radius: 8px;
}

.permission-note h3 {
  margin: 0 0 12px;

  color: #1f2d3d;
  font-size: 16px;
}

.permission-note p {
  margin: 10px 0 0;
}

:deep(.prototype-dialog .el-dialog__header) {
  padding: 22px 28px 16px;

  border-bottom: 1px solid #edf1f5;
}

:deep(.prototype-dialog .el-dialog__body) {
  padding: 20px 28px 16px;
}

:deep(.prototype-dialog .el-dialog__footer) {
  padding: 16px 28px 20px;

  border-top: 1px solid #edf1f5;
}

/*
 * 中等屏幕：仍然保持查询区单行，
 * 只缩小间距和按钮宽度。
 */
@media (width <= 1180px) {
  .user-filter {
    gap: 10px;
    padding-right: 12px;
    padding-left: 12px;
  }

  .user-filter-fields {
    gap: 8px;
  }

  .filter-actions {
    gap: 6px;
  }

  .filter-actions .el-button {
    min-width: 62px;
    padding-right: 8px;
    padding-left: 8px;
  }
}

/*
 * 小屏幕依然是：
 * 用户名/姓名 | 绑定城市 | 账号状态 | 查询 | 重置
 * 全部保持同一行。
 */
@media (width <= 860px) {
  .user-filter {
    gap: 6px;
    padding: 12px 10px;
  }

  .user-filter-fields {
    gap: 6px;
  }

  .user-filter-fields label {
    gap: 4px;
    font-size: 13px;
  }

  .filter-actions {
    gap: 4px;
  }

  .filter-actions .el-button {
    min-width: 54px;
    padding-right: 6px;
    padding-left: 6px;
  }

  /*
   * 这里只让新增/编辑弹窗在小屏变单列，
   * 不影响上面的用户查询区。
   */
  .dialog-grid {
    grid-template-columns: 1fr;
  }
}

@media (width <= 640px) {
  /*
   * 极窄宽度仍不把查询区改成多行。
   * 字段继续等宽压缩，按钮仍靠最右。
   */
  .user-filter {
    gap: 5px;
    padding-right: 8px;
    padding-left: 8px;
  }

  .user-filter-fields {
    gap: 5px;
  }

  .filter-actions {
    gap: 4px;
  }

  .filter-actions .el-button {
    min-width: 50px;
    padding-right: 5px;
    padding-left: 5px;
  }

  .user-table footer {
    width: 100%;

    align-items: flex-start;
    flex-direction: column;
  }
}


/*
 * ============================================================
 * 用户管理查询区最终布局
 * ============================================================
 * 目标：
 * 1. 用户名/姓名、绑定城市、账号状态始终同一行；
 * 2. 三个字段均匀分配并占满可用空间；
 * 3. 查询、重置始终靠最右；
 * 4. 大屏、小屏都不改成纵向堆叠；
 * 5. 只点击“查询”按钮后才真正应用筛选条件。
 *
 * 这里使用专用 class + !important，
 * 防止项目中的通用筛选区样式覆盖当前页面。
 */

.user-query-bar-final {
  display: grid !important;

  width: 100% !important;
  min-width: 0 !important;

  grid-template-columns:
    minmax(0, 1fr)
    max-content !important;

  gap: 12px !important;
  align-items: end !important;

  padding: 14px 16px !important;
  margin-bottom: 14px !important;

  overflow: visible !important;
  box-sizing: border-box !important;
}

.user-query-fields-final {
  display: grid !important;

  width: 100% !important;
  min-width: 0 !important;

  grid-template-columns:
    repeat(3, minmax(0, 1fr)) !important;

  grid-auto-flow: column !important;
  grid-auto-columns: minmax(0, 1fr) !important;

  gap: 10px !important;
  align-items: end !important;

  flex: none !important;
}

.user-query-fields-final > label {
  display: grid !important;

  width: auto !important;
  min-width: 0 !important;
  max-width: none !important;

  grid-column: auto !important;

  gap: 6px !important;

  margin: 0 !important;
}

.user-query-fields-final > label > span {
  display: block !important;

  min-width: 0 !important;

  white-space: nowrap !important;
}

.user-query-fields-final :deep(.el-input),
.user-query-fields-final :deep(.el-select),
.user-query-fields-final :deep(.el-date-editor) {
  display: block !important;

  width: 100% !important;
  min-width: 0 !important;
  max-width: none !important;
}

.user-query-fields-final :deep(.el-input__wrapper),
.user-query-fields-final :deep(.el-select__wrapper) {
  width: 100% !important;
  min-width: 0 !important;

  box-sizing: border-box !important;
}

.user-query-actions-final {
  display: flex !important;

  width: auto !important;
  min-width: max-content !important;

  grid-column: 2 !important;
  grid-row: 1 !important;

  flex: none !important;
  flex-direction: row !important;
  flex-wrap: nowrap !important;

  gap: 8px !important;
  align-items: center !important;
  justify-content: flex-end !important;
  align-self: end !important;

  margin: 0 0 0 auto !important;

  white-space: nowrap !important;
}

.user-query-actions-final .el-button {
  width: auto !important;
  min-width: 68px !important;

  flex: 0 0 auto !important;

  margin: 0 !important;
  padding-right: 10px !important;
  padding-left: 10px !important;
}

/*
 * 中等宽度：仍然保持同一行，只压缩间距。
 */
@media (width <= 1180px) {
  .user-query-bar-final {
    gap: 10px !important;

    padding-right: 12px !important;
    padding-left: 12px !important;
  }

  .user-query-fields-final {
    gap: 8px !important;
  }

  .user-query-actions-final {
    gap: 6px !important;
  }

  .user-query-actions-final .el-button {
    min-width: 62px !important;

    padding-right: 8px !important;
    padding-left: 8px !important;
  }
}

/*
 * 小屏：仍然是
 * 用户名/姓名 | 绑定城市 | 账号状态 | 查询 | 重置
 * 不允许变成三行。
 */
@media (width <= 860px) {
  .user-query-bar-final {
    gap: 6px !important;
    padding: 12px 10px !important;
  }

  .user-query-fields-final {
    gap: 6px !important;
  }

  .user-query-fields-final > label {
    gap: 4px !important;

    font-size: 13px !important;
  }

  .user-query-actions-final {
    gap: 4px !important;
  }

  .user-query-actions-final .el-button {
    min-width: 54px !important;

    padding-right: 6px !important;
    padding-left: 6px !important;
  }
}

/*
 * 极窄宽度也不纵向堆叠。
 * 三个字段继续平均压缩，按钮始终在最右侧。
 */
@media (width <= 640px) {
  .user-query-bar-final {
    grid-template-columns:
      minmax(0, 1fr)
      max-content !important;

    gap: 5px !important;

    padding-right: 8px !important;
    padding-left: 8px !important;
  }

  .user-query-fields-final {
    grid-template-columns:
      repeat(3, minmax(0, 1fr)) !important;

    gap: 5px !important;
  }

  .user-query-actions-final {
    flex-direction: row !important;

    gap: 4px !important;
  }

  .user-query-actions-final .el-button {
    width: auto !important;
    min-width: 50px !important;

    padding-right: 5px !important;
    padding-left: 5px !important;
  }
}

</style>
