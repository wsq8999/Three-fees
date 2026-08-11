import { createRouter, createWebHistory } from "vue-router";

import type { UserRole } from "@/api/contracts";
import { useSessionStore } from "@/stores/session";

import { canAccessRoute } from "./access";

declare module "vue-router" {
  interface RouteMeta {
    title: string;
    requiresAuth?: boolean;
    requiredRoles?: UserRole[];
  }
}

export const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: "/login",
      name: "login",
      component: () => import("@/views/login/LoginView.vue"),
      meta: { title: "登录" },
    },
    {
      path: "/",
      component: () => import("@/layouts/AdminLayout.vue"),
      meta: { title: "管理台", requiresAuth: true },
      children: [
        { path: "", redirect: "/dashboard" },
        {
          path: "dashboard",
          name: "dashboard",
          component: () => import("@/views/dashboard/DashboardView.vue"),
          meta: { title: "工作台", requiresAuth: true },
        },
        {
          path: "imports",
          name: "imports-compat",
          redirect: { path: "/billing-points", query: { dialog: "import" } },
          meta: { title: "导入数据", requiresAuth: true },
        },
        {
          path: "billing-points",
          name: "billing-points",
          component: () =>
            import("@/views/billing-points/BillingPointsView.vue"),
          meta: { title: "报账点管理", requiresAuth: true },
        },
        {
          path: "billing-points/:billingPointCode/periods/:period",
          name: "billing-point-detail",
          component: () =>
            import("@/views/billing-points/BillingPointDetailView.vue"),
          meta: { title: "报账点详情", requiresAuth: true },
        },
        {
          path: "reports/drafts/:draftId",
          name: "report-draft",
          component: () => import("@/views/reports/ReportDraftView.vue"),
          meta: { title: "生成报告", requiresAuth: true },
        },
        {
          path: "reports",
          name: "reports-compat",
          redirect: "/reports/generate",
          meta: { title: "稽核报告管理", requiresAuth: true },
        },
        {
          path: "reports/generate",
          name: "reports-generate",
          component: () => import("@/views/reports/GenerateReportsView.vue"),
          meta: { title: "生成报告", requiresAuth: true },
        },
        {
          path: "reports/history",
          name: "reports-history",
          component: () => import("@/views/reports/ReportsView.vue"),
          meta: { title: "历史报告", requiresAuth: true },
        },
        {
          path: "reports/:reportId",
          name: "report-detail",
          component: () => import("@/views/reports/ReportDetailView.vue"),
          meta: { title: "报告详情", requiresAuth: true },
        },
        {
          path: "reports/:reportId/correction",
          name: "report-correction",
          component: () => import("@/views/reports/ReportDetailView.vue"),
          props: { correction: true },
          meta: { title: "报告更正", requiresAuth: true },
        },
        {
          path: "benchmark-rules",
          name: "benchmark-rules",
          component: () => import("@/views/rules/BenchmarkRulesView.vue"),
          meta: { title: "标杆规则管理", requiresAuth: true },
        },
        {
          path: "users",
          name: "users",
          component: () => import("@/views/users/UsersView.vue"),
          meta: {
            title: "用户管理",
            requiresAuth: true,
            requiredRoles: ["SUPER_ADMIN"],
          },
        },
        {
          path: "forbidden",
          name: "forbidden",
          component: () => import("@/views/errors/ForbiddenView.vue"),
          meta: { title: "无权访问", requiresAuth: true },
        },
      ],
    },
    { path: "/:pathMatch(.*)*", redirect: "/dashboard" },
  ],
});

router.beforeEach(async (to) => {
  const session = useSessionStore();
  if (session.status === "idle") {
    await session.restore();
  }

  if (to.name === "login") {
    return session.isAuthenticated ? { name: "dashboard" } : true;
  }
  if (to.meta.requiresAuth && !session.isAuthenticated) {
    return { name: "login", query: { redirect: to.fullPath } };
  }
  const roles = session.currentUser?.roles ?? [];
  if (!canAccessRoute(roles, to.meta.requiredRoles)) {
    return { name: "forbidden" };
  }
  return true;
});
