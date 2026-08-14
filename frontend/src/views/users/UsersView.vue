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

const filters = reactive({
  keyword: "",
  cityCode: "",
  enabled: "",
  page: 1,
  size: 10,
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
  const keyword = filters.keyword.trim().toLowerCase();
  return (pageData.value?.items ?? []).filter((user) => {
    const matchedKeyword =
      keyword.length === 0 ||
      [user.username, user.displayName].some((value) =>
        value.toLowerCase().includes(keyword),
      );
    const matchedCity =
      filters.cityCode.length === 0 || user.city?.code === filters.cityCode;
    const matchedStatus =
      filters.enabled.length === 0 || String(user.enabled) === filters.enabled;
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

function resetFilters(): void {
  Object.assign(filters, { keyword: "", cityCode: "", enabled: "", page: 1 });
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
    formError.value = "请完整填写用户名、用户名称和绑定城市。";
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
    formError.value = "用户名称不能为空。";
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
  Object.assign(passwordForm, { newPassword: "", confirmPassword: "" });
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
    passwordError.value = error instanceof Error ? error.message : "密码修改失败";
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
  <section class="user-filter business-card">
    <label>
      <span>用户名/姓名</span>
      <ElInput
        v-model="filters.keyword"
        placeholder="请输入关键词"
        clearable
        @keyup.enter="load"
      />
    </label>
    <label>
      <span>绑定城市</span>
      <ElSelect v-model="filters.cityCode" placeholder="全部城市" clearable>
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
      <ElSelect v-model="filters.enabled" placeholder="全部" clearable>
        <ElOption label="启用" value="true" />
        <ElOption label="停用" value="false" />
      </ElSelect>
    </label>
    <div class="filter-actions">
      <ElButton type="primary" :icon="Search" :loading="loading" @click="load">
        查询
      </ElButton>
      <ElButton :icon="Refresh" @click="resetFilters">重置</ElButton>
    </div>
  </section>

  <div class="business-toolbar">
    <ElButton type="primary" :icon="Plus" @click="openCreate">新增用户</ElButton>
    <span>共 {{ filteredItems.length }} 用户</span>
  </div>

  <PageState v-if="!pageData && loading" kind="loading" />
  <PageState
    v-else-if="errorMessage"
    kind="error"
    title="用户列表加载失败"
    :description="errorMessage"
    @retry="load"
  />

  <section v-else class="user-table business-card">
    <ElTable v-loading="loading" :data="filteredItems">
      <ElTableColumn prop="username" label="用户名" width="155" />
      <ElTableColumn prop="displayName" label="用户名称" min-width="180" />
      <ElTableColumn label="角色" width="155">
        <template #default="scope">{{ roleLabel(asManagedUser(scope.row)) }}</template>
      </ElTableColumn>
      <ElTableColumn label="数据范围" min-width="155">
        <template #default="scope">{{ dataScope(asManagedUser(scope.row)) }}</template>
      </ElTableColumn>
      <ElTableColumn label="账号状态" width="110">
        <template #default="scope">
          <ElTag :type="scope.row.enabled ? 'success' : 'info'">
            {{ scope.row.enabled ? "启用" : "停用" }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="最后登录时间" width="170">
        <template #default="scope">{{ formatTime(asManagedUser(scope.row).updatedAt) }}</template>
      </ElTableColumn>
      <ElTableColumn label="操作" min-width="230" fixed="right">
        <template #default="scope">
          <ElButton link type="danger" @click="openEdit(asManagedUser(scope.row))">
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

    <ElAlert
      title="市级业务用户拥有本城市内全部业务操作权限，但不能进入用户管理；超级管理员可查看全省数据并维护用户。"
      type="info"
      :closable="false"
      show-icon
    />

    <footer>
      <span>已显示 {{ filteredItems.length }} 条，共 {{ pageData?.totalElements ?? 0 }} 条</span>
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
        <ElFormItem label="用户名称 *">
          <ElInput
            v-model="createForm.displayName"
            maxlength="50"
            placeholder="请输入用户名称"
          />
        </ElFormItem>
        <ElFormItem label="角色 *">
          <ElInput model-value="市级业务用户" disabled />
        </ElFormItem>
        <ElFormItem label="绑定城市 *">
          <ElSelect v-model="createForm.cityCode" placeholder="请选择城市">
            <ElOption
              v-for="city in cities"
              :key="city.code"
              :label="city.name"
              :value="city.code"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="初始密码 *">
          <ElInput v-model="createForm.initialPassword" type="password" show-password />
        </ElFormItem>
        <ElFormItem label="确认初始密码 *">
          <ElInput v-model="createForm.confirmPassword" type="password" show-password />
        </ElFormItem>
        <ElFormItem label="账号状态 *">
          <ElSelect v-model="createForm.enabled">
            <ElOption label="启用" :value="true" />
            <ElOption label="停用" :value="false" />
          </ElSelect>
        </ElFormItem>
      </div>

      <section class="permission-note">
        <h3>权限说明</h3>
        <p>✓ 可查看并操作绑定城市的工作台、报账点、数据导入导出、稽核报告和AI助手。</p>
        <p>🔒 不可查看其他城市数据，不可进入用户管理或给其他用户授权。</p>
      </section>

      <ElAlert
        v-if="formError"
        :title="formError"
        type="error"
        :closable="false"
        show-icon
      />
    </ElForm>
    <template #footer>
      <ElButton @click="createVisible = false">取消</ElButton>
      <ElButton type="primary" :loading="saving" @click="createUser">
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
        <ElInput :model-value="editingUser?.username" disabled />
      </ElFormItem>
      <ElFormItem label="用户名称 *">
        <ElInput v-model="editForm.displayName" maxlength="50" />
      </ElFormItem>
      <ElFormItem label="数据范围">
        <ElInput :model-value="editingUser ? dataScope(editingUser) : ''" disabled />
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
      <ElButton @click="editVisible = false">取消</ElButton>
      <ElButton type="primary" :loading="saving" @click="saveEdit">保存</ElButton>
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
      <ElFormItem label="用户">
        <ElInput :model-value="passwordUser?.displayName ?? ''" disabled />
      </ElFormItem>
      <ElFormItem label="新密码 *">
        <ElInput v-model="passwordForm.newPassword" type="password" show-password />
      </ElFormItem>
      <ElFormItem label="确认新密码 *">
        <ElInput v-model="passwordForm.confirmPassword" type="password" show-password />
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
      <ElButton @click="passwordVisible = false">取消</ElButton>
      <ElButton type="primary" :loading="saving" @click="resetPassword">确认</ElButton>
    </template>
  </ElDialog>
</template>

<style scoped>
.user-filter {
  display: grid;
  grid-template-columns: minmax(min(100%, 260px), 2fr) repeat(2, minmax(min(100%, 150px), 1fr));
  gap: 14px;
  align-items: end;
  padding: 16px;
  margin-bottom: 14px;
}

.user-filter label {
  display: grid;
  gap: 6px;
  color: #1f2d3d;
  font-size: 14px;
  font-weight: 600;
}

.filter-actions {
  display: flex;
  flex-wrap: wrap;
  grid-column: 1 / -1;
  gap: 10px;
  justify-content: flex-end;
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

@media (width <= 860px) {
  .user-filter,
  .dialog-grid {
    grid-template-columns: 1fr;
  }

  .filter-actions {
    justify-content: flex-end;
  }
}

@media (width <= 640px) {
  .filter-actions,
  .filter-actions .el-button,
  .user-table footer {
    width: 100%;
  }

  .user-table footer {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
