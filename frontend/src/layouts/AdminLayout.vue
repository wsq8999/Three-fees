<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ArrowDown,
  Fold,
  HomeFilled,
  Key,
  SwitchButton,
} from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";

import { businessApi } from "@/api/business-api";
import NavigationMenu from "@/components/NavigationMenu.vue";
import { useSessionStore } from "@/stores/session";

const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const isDrawerOpen = ref(false);
const isCollapsed = ref(false);
const isLoggingOut = ref(false);
const isChangingPassword = ref(false);
const passwordDialogVisible = ref(false);
const passwordError = ref("");
const passwordForm = reactive({ current: "", next: "", confirmation: "" });

const user = computed(() => session.currentUser);
const avatar = computed(() => user.value?.displayName.slice(0, 1) ?? "超");
const breadcrumbTitle = computed(() => String(route.meta.title ?? ""));
const breadcrumbItems = computed(() => {
  if (route.name === "billing-point-detail") {
    return ["报账点管理", "报账点详情"];
  }
  if (route.name === "report-detail") {
    return ["稽核报告管理", "历史报告", "报告详情"];
  }
  return [breadcrumbTitle.value];
});

async function handleLogout(): Promise<void> {
  if (isLoggingOut.value) return;
  isLoggingOut.value = true;
  try {
    await session.logout();
  } finally {
    isLoggingOut.value = false;
    await router.replace("/login");
  }
}

function openPasswordDialog(): void {
  Object.assign(passwordForm, { current: "", next: "", confirmation: "" });
  passwordError.value = "";
  passwordDialogVisible.value = true;
}

async function submitPassword(): Promise<void> {
  passwordError.value = "";
  if (
    !passwordForm.current ||
    !passwordForm.next ||
    !passwordForm.confirmation
  ) {
    passwordError.value = "请完整填写三个密码字段。";
    return;
  }
  if (passwordForm.next.length < 8) {
    passwordError.value = "新密码至少 8 位。";
    return;
  }
  if (passwordForm.next !== passwordForm.confirmation) {
    passwordError.value = "两次输入的新密码不一致。";
    return;
  }
  if (user.value === null) return;
  isChangingPassword.value = true;
  try {
    await businessApi.users.changeOwnPassword(
      user.value.id,
      passwordForm.current,
      passwordForm.next,
      passwordForm.confirmation,
    );
    passwordDialogVisible.value = false;
    ElMessage.success("密码已修改，请重新登录。");
    await handleLogout();
  } catch (error) {
    passwordError.value =
      error instanceof Error ? error.message : "修改密码失败";
  } finally {
    isChangingPassword.value = false;
  }
}

function handleUserCommand(command: string): void {
  if (command === "password") openPasswordDialog();
  if (command === "logout") void handleLogout();
}
</script>

<template>
  <div class="admin-layout" :class="{ collapsed: isCollapsed }">
    <aside class="desktop-sidebar" aria-label="主导航">
      <RouterLink class="brand-lockup" to="/dashboard">
        <span class="brand-mark" aria-hidden="true"><HomeFilled /></span>
        <strong>智能物业管理系统</strong>
      </RouterLink>
      <NavigationMenu :roles="user?.roles ?? []" :collapsed="isCollapsed" />
      <button
        class="collapse-navigation"
        type="button"
        @click="isCollapsed = !isCollapsed"
      >
        <Fold />
        <span>{{ isCollapsed ? "展开导航" : "收起导航" }}</span>
        <small>{{ isCollapsed ? "›" : "‹" }}</small>
      </button>
    </aside>

    <ElDrawer
      v-model="isDrawerOpen"
      class="navigation-drawer"
      direction="ltr"
      :size="240"
      :with-header="false"
    >
      <div class="drawer-navigation">
        <RouterLink class="brand-lockup" to="/dashboard">
          <span class="brand-mark"><HomeFilled /></span>
          <strong>智能物业管理系统</strong>
        </RouterLink>
        <NavigationMenu
          :roles="user?.roles ?? []"
          @navigated="isDrawerOpen = false"
        />
      </div>
    </ElDrawer>

    <div class="workspace">
      <header class="topbar">
        <div class="topbar-leading">
          <ElButton
            class="mobile-navigation-button"
            text
            aria-label="打开主导航"
            @click="isDrawerOpen = true"
          >
            <ElIcon :size="20"><Fold /></ElIcon>
          </ElButton>
          <ElBreadcrumb separator="/">
            <ElBreadcrumbItem :to="{ path: '/dashboard' }">
              <ElIcon><HomeFilled /></ElIcon>
            </ElBreadcrumbItem>
            <ElBreadcrumbItem v-for="item in breadcrumbItems" :key="item">
              {{ item }}
            </ElBreadcrumbItem>
          </ElBreadcrumb>
        </div>
        <ElDropdown trigger="click" @command="handleUserCommand">
          <button class="user-menu" type="button" aria-label="打开用户菜单">
            <span class="avatar">{{ avatar }}</span>
            <span>{{ user?.displayName }}</span>
            <ElIcon><ArrowDown /></ElIcon>
          </button>
          <template #dropdown>
            <ElDropdownMenu>
              <ElDropdownItem command="password" :icon="Key">
                修改密码
              </ElDropdownItem>
              <ElDropdownItem
                command="logout"
                :icon="SwitchButton"
                :disabled="isLoggingOut"
                divided
              >
                退出登录
              </ElDropdownItem>
            </ElDropdownMenu>
          </template>
        </ElDropdown>
      </header>

      <main class="main-content">
        <RouterView />
      </main>
    </div>

    <ElDialog
      v-model="passwordDialogVisible"
      title="修改密码"
      width="480px"
      class="centered-dialog"
      append-to-body
      align-center
      :close-on-click-modal="false"
    >
      <ElForm label-position="top" @submit.prevent="submitPassword">
        <ElFormItem label="当前密码" required>
          <ElInput
            v-model="passwordForm.current"
            type="password"
            show-password
          />
        </ElFormItem>
        <ElFormItem label="新密码" required>
          <ElInput v-model="passwordForm.next" type="password" show-password />
        </ElFormItem>
        <ElFormItem label="确认新密码" required>
          <ElInput
            v-model="passwordForm.confirmation"
            type="password"
            show-password
            @keyup.enter="submitPassword"
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
        <ElButton @click="passwordDialogVisible = false">取消</ElButton>
        <ElButton
          type="primary"
          :loading="isChangingPassword"
          @click="submitPassword"
        >
          保存修改
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.admin-layout {
  min-height: 100vh;
  background:
    radial-gradient(circle at 82% 92%, rgb(255 49 78 / 5%), transparent 28%),
    var(--color-neutral-50);
}

.admin-layout.collapsed {
  --sidebar-width: 68px;
}

.desktop-sidebar {
  position: fixed;
  inset: 0 auto 0 0;
  z-index: var(--z-navigation);
  width: var(--sidebar-width);
  background: var(--color-neutral-0);
  border-right: 1px solid var(--color-neutral-200);
}

.brand-lockup {
  display: flex;
  height: var(--topbar-height);

  /* 缩小图标与标题之间的间距 */
  gap: 8px;

  align-items: center;

  /* 原来左右16px，改成12px，给完整系统名称腾出空间 */
  padding: var(--space-2) 12px;

  color: var(--color-neutral-900);
  border-bottom: 1px solid var(--color-neutral-200);
}

.brand-lockup strong {
  flex: 1 1 auto;
  min-width: 0;

  /*
   * 不再使用 ... 截断系统名称
   */
  overflow: visible;
  font-size: 15px;
  font-weight: 800;
  text-overflow: clip;
  white-space: nowrap;
}

.brand-mark {
  display: grid;
  width: 36px;
  height: 36px;
  flex: none;
  place-items: center;
  color: white;
  background: linear-gradient(145deg, #ff4b59, var(--color-brand-600));
  border-radius: 10px;
  box-shadow: 0 10px 20px rgb(245 34 45 / 22%);
}

.brand-mark :deep(svg) {
  width: 20px;
}

.collapse-navigation {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  height: 48px;
  gap: var(--space-3);
  align-items: center;
  padding: 0 var(--space-5);
  color: var(--color-neutral-600);
  background: white;
  border: 0;
  border-top: 1px solid var(--color-neutral-200);
}

.collapsed .brand-lockup {
  justify-content: center;
  padding-inline: 0;
}

.collapsed .brand-lockup strong,
.collapsed .collapse-navigation span,
.collapsed .collapse-navigation small {
  display: none;
}

.collapsed .collapse-navigation {
  justify-content: center;
  padding: 0;
}

.collapse-navigation svg {
  width: 16px;
}

.collapse-navigation small {
  margin-left: auto;
}

.workspace {
  min-width: 0;
  margin-left: var(--sidebar-width);
}

.topbar {
  position: sticky;
  top: 0;
  z-index: calc(var(--z-navigation) - 1);
  display: flex;
  min-height: var(--topbar-height);
  align-items: center;
  justify-content: space-between;
  padding: var(--space-2) var(--space-6);
  background: rgb(255 255 255 / 96%);
  border-bottom: 1px solid var(--color-neutral-200);
  backdrop-filter: blur(10px);
}

.topbar-leading,
.user-menu {
  display: flex;
  align-items: center;
  min-width: 0;
}

.topbar-leading {
  gap: var(--space-2);
}

.user-menu {
  gap: var(--space-2);
  padding: 0;
  color: var(--color-neutral-700);
  background: transparent;
  border: 0;
  cursor: pointer;
}

.avatar {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  color: white;
  background: #26384e;
  border-radius: 50%;
}

.mobile-navigation-button {
  display: none;
}

.main-content {
  width: min(100%, var(--content-max-width));
  min-width: 0;
  min-height: calc(100vh - var(--topbar-height));
  padding: var(--page-padding);
  margin: 0 auto;
}

.drawer-navigation {
  min-height: 100vh;
  background: white;
}

:global(.navigation-drawer) {
  --el-drawer-padding-primary: 0;
}

@media (width <= 960px) {
  .desktop-sidebar {
    display: none;
  }

  .workspace {
    margin-left: 0;
  }

  .mobile-navigation-button {
    display: inline-flex;
  }
}

@media (width <= 640px) {
  .topbar {
    padding-inline: var(--space-3);
  }

  .main-content {
    padding: 12px;
  }

  .topbar :deep(.el-breadcrumb),
  .user-menu > span:nth-child(2) {
    display: none;
  }
}
</style>

:deep(.centered-dialog) {
  max-height: calc(100vh - 48px);
  margin: 0 auto;
  display: flex;
  flex-direction: column;
}

:deep(.centered-dialog .el-dialog__body) {
  overflow: auto;
}
