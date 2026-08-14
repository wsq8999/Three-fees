<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";
import {
  DataAnalysis,
  Document,
  Location,
  Odometer,
  User,
} from "@element-plus/icons-vue";

import type { UserRole } from "@/api/contracts";
import { resolveNavigationActivePath } from "@/router/navigation-active";

const props = withDefaults(
  defineProps<{ roles: readonly UserRole[]; collapsed?: boolean }>(),
  { collapsed: false },
);
defineEmits<{ navigated: [] }>();

const route = useRoute();
const canManageUsers = computed(() => props.roles.includes("SUPER_ADMIN"));
const activePath = computed(() => resolveNavigationActivePath(route.path));
</script>

<template>
  <ElMenu
    :default-active="activePath"
    class="navigation-menu"
    :collapse="collapsed"
    :collapse-transition="false"
    router
    @select="$emit('navigated')"
  >
    <ElMenuItem index="/dashboard">
      <ElIcon><Odometer /></ElIcon><span>工作台</span>
    </ElMenuItem>
    <ElMenuItem index="/billing-points">
      <ElIcon><Location /></ElIcon><span>报账点管理</span>
    </ElMenuItem>
    <ElSubMenu index="reports">
      <template #title>
        <ElIcon><Document /></ElIcon><span>稽核报告管理</span>
      </template>
      <ElMenuItem index="/reports/generate">生成报告</ElMenuItem>
      <ElMenuItem index="/reports/history">历史报告</ElMenuItem>
    </ElSubMenu>
    <ElMenuItem index="/benchmark-rules">
      <ElIcon><DataAnalysis /></ElIcon><span>标杆规则管理</span>
    </ElMenuItem>
    <ElMenuItem v-if="canManageUsers" index="/users">
      <ElIcon><User /></ElIcon><span>用户管理</span>
    </ElMenuItem>
  </ElMenu>
</template>

<style scoped>
.navigation-menu {
  padding-top: 10px;
  border-right: 0;
  background: transparent;
}

.navigation-menu :deep(.el-menu-item),
.navigation-menu :deep(.el-sub-menu__title) {
  height: 52px;
  margin: 0 0 var(--space-1);
  color: var(--color-neutral-700);
  border-left: 3px solid transparent;
}

.navigation-menu :deep(.el-menu-item:hover),
.navigation-menu :deep(.el-sub-menu__title:hover),
.navigation-menu :deep(.el-menu-item.is-active),
.navigation-menu :deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: var(--color-brand-600);
  background: var(--color-brand-50);
  border-left-color: var(--color-brand-600);
}

.navigation-menu :deep(.el-sub-menu .el-menu-item) {
  min-width: 0;
  height: 40px;
  padding-left: 54px !important;
  font-size: var(--font-size-sm);
}

.navigation-menu.el-menu--collapse :deep(.el-menu-item),
.navigation-menu.el-menu--collapse :deep(.el-sub-menu__title) {
  justify-content: center;
  padding: 0 !important;
}
</style>
