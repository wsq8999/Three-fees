<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import {
  DataAnalysis,
  HomeFilled,
  Lock,
  Management,
  User,
  View,
} from "@element-plus/icons-vue";

import { useSessionStore } from "@/stores/session";

const router = useRouter();
const session = useSessionStore();
const isSubmitting = ref(false);
const pageError = ref("");
const rememberUsername = ref(false);
const forgotVisible = ref(false);
const fieldErrors = reactive({ username: "", password: "" });
const form = reactive({ username: "", password: "" });

function validate(): boolean {
  fieldErrors.username = form.username.trim() === "" ? "请输入用户名" : "";
  fieldErrors.password =
    form.password.length < 6 ? "密码至少需要 6 个字符" : "";
  return fieldErrors.username === "" && fieldErrors.password === "";
}

async function handleSubmit(): Promise<void> {
  pageError.value = "";
  if (!validate() || isSubmitting.value) return;
  isSubmitting.value = true;
  const result = await session.login({
    username: form.username.trim(),
    password: form.password,
  });
  isSubmitting.value = false;
  if (result.ok) {
    /*
     * “暂无报账点，请导入数据”的提醒只在
     * 当前一次登录期间关闭。
     *
     * 每次重新登录时清除上一次登录留下的
     * sessionStorage 标记。
     *
     * Dashboard 仍然会根据 billingPointCount 判断：
     * - 有报账点：不弹；
     * - 没有报账点：本次登录弹一次。
     */
    const loggedInUser = session.currentUser;

    const importGuideUserId =
      loggedInUser?.id ??
      loggedInUser?.username ??
      form.username.trim();

    sessionStorage.removeItem(
      `three-fees-import-guide-dismissed:${importGuideUserId}`,
    );

    if (rememberUsername.value) {
      localStorage.setItem(
        "three-fees-remembered-username",
        form.username.trim(),
      );
    } else {
      localStorage.removeItem(
        "three-fees-remembered-username",
      );
    }

    form.password = "";
    await router.replace("/dashboard");
    return;
  }
  form.password = "";
  pageError.value =
    result.problem.code === "AUTHENTICATION_FAILED"
      ? "账号或口令不正确，请重新输入。"
      : "暂时无法登录，请稍后重试。";
}

onMounted(() => {
  const remembered = localStorage.getItem("three-fees-remembered-username");
  if (remembered) {
    form.username = remembered;
    rememberUsername.value = true;
  }
});
</script>

<template>
  <main class="login-page">
    <section class="login-introduction" aria-labelledby="product-name">
      <div class="brand">
        <span><HomeFilled /></span><strong>智能物业管理系统</strong>
      </div>
      <div class="introduction-content">
        <h1 id="product-name">电费稽核业务工作台</h1>
        <i />
        <p>江苏省市级用户统一入口</p>
        <div class="security-illustration" aria-hidden="true">
          <span class="shield">✓</span>
          <span class="orbit one" /><span class="orbit two" />
        </div>
        <ul>
          <li>
            <span><View /></span><b>智能稽核</b
            ><small>自动校验，精准高效</small>
          </li>
          <li>
            <span><Lock /></span><b>数据安全</b
            ><small>多重防护，安全可靠</small>
          </li>
          <li>
            <span><DataAnalysis /></span><b>统计分析</b
            ><small>多维报表，洞察趋势</small>
          </li>
          <li>
            <span><Management /></span><b>协同管理</b
            ><small>流程闭环，高效协同</small>
          </li>
        </ul>
      </div>
    </section>

    <section class="login-panel" aria-labelledby="login-heading">
      <div class="login-card">
        <span class="login-mark"><HomeFilled /></span>
        <h2 id="login-heading">欢迎登录</h2>
        <p class="login-description">智能物业管理系统</p>
        <i class="title-line" />
        <div
          v-if="pageError"
          class="login-error"
          role="alert"
          aria-live="polite"
        >
          {{ pageError }}
        </div>
        <form novalidate @submit.prevent="handleSubmit">
          <div class="form-field">
            <label for="username">用户名</label>
            <ElInput
              id="username"
              v-model="form.username"
              data-testid="username-input"
              autocomplete="username"
              placeholder="请输入用户名"
              :aria-invalid="fieldErrors.username !== ''"
              size="large"
              @input="fieldErrors.username = ''"
              ><template #prefix
                ><ElIcon><User /></ElIcon></template
            ></ElInput>
            <span v-if="fieldErrors.username" class="field-error">{{
              fieldErrors.username
            }}</span>
          </div>
          <div class="form-field">
            <label for="password">密码</label>
            <ElInput
              id="password"
              v-model="form.password"
              data-testid="password-input"
              type="password"
              autocomplete="current-password"
              placeholder="请输入密码"
              show-password
              :aria-invalid="fieldErrors.password !== ''"
              size="large"
              @input="fieldErrors.password = ''"
              ><template #prefix
                ><ElIcon><Lock /></ElIcon></template
            ></ElInput>
            <span v-if="fieldErrors.password" class="field-error">{{
              fieldErrors.password
            }}</span>
          </div>
          <div class="login-options">
            <ElCheckbox v-model="rememberUsername">记住用户名</ElCheckbox
            ><ElButton link type="danger" @click="forgotVisible = true"
              >忘记密码</ElButton
            >
          </div>
          <ElButton
            class="login-submit"
            data-testid="login-submit"
            type="primary"
            native-type="submit"
            size="large"
            :loading="isSubmitting"
            :disabled="isSubmitting"
            >登录</ElButton
          >
        </form>
        <p class="security-note">
          账号由系统管理员分配，如需帮助请联系系统管理员
        </p>
      </div>
    </section>

    <ElDialog v-model="forgotVisible" title="忘记密码" width="430px">
      <ElAlert
        title="当前系统不提供短信或邮件找回。请联系系统超级管理员执行密码重置；实际临时口令不会在此页面展示。"
        type="info"
        :closable="false"
        show-icon
      />
      <template #footer
        ><ElButton type="primary" @click="forgotVisible = false"
          >我知道了</ElButton
        ></template
      >
    </ElDialog>
  </main>
</template>

<style scoped>
.login-page {
  display: grid;
  min-height: 100vh;
  grid-template-columns: 1fr 1fr;
  background: #fbfcff;
}

.login-introduction {
  position: relative;
  min-height: 100vh;
  padding: 56px 64px 34px;
  overflow: hidden;
  background:
    radial-gradient(circle at 20% 20%, rgb(245 34 45 / 7%), transparent 34%),
    linear-gradient(150deg, #fff 0%, #fff9fa 62%, #ffe7ea 100%);
  border-right: 1px solid var(--color-neutral-200);
}

.login-introduction::after {
  position: absolute;
  right: -80px;
  bottom: -160px;
  width: 400px;
  height: 400px;
  content: "";
  background: rgb(245 34 45 / 8%);
  border-radius: 50%;
}

.brand {
  position: relative;
  z-index: 1;
  display: flex;
  gap: var(--space-3);
  align-items: center;
  font-size: var(--font-size-lg);
}

.brand span,
.login-mark {
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  color: white;
  background: linear-gradient(145deg, #ff4d5a, var(--color-brand-600));
  border-radius: 12px;
  box-shadow: 0 8px 20px rgb(245 34 45 / 22%);
}

.brand svg,
.login-mark svg {
  width: 26px;
}

.introduction-content {
  position: relative;
  z-index: 1;
  height: calc(100% - 50px);
  padding: 120px 34px 0;
}

.introduction-content h1 {
  margin: 0;
  color: #152337;
  font-size: clamp(34px, 3.2vw, 52px);
  letter-spacing: 0.06em;
}

.introduction-content > i,
.title-line {
  display: block;
  width: 54px;
  height: 4px;
  margin: 26px 0;
  background: var(--color-brand-600);
  border-radius: 99px;
}

.introduction-content > p {
  color: var(--color-neutral-500);
  font-size: var(--font-size-xl);
}

.security-illustration {
  position: relative;
  display: grid;
  width: min(100%, 330px);
  height: 220px;
  margin: 35px auto 20px;
  place-items: center;
}

.shield {
  position: relative;
  z-index: 2;
  display: grid;
  width: 116px;
  height: 136px;
  place-items: center;
  color: white;
  background: linear-gradient(150deg, #ffadb4, var(--color-brand-500));
  border: 9px solid rgb(255 255 255 / 70%);
  border-radius: 55% 55% 48% 48%;
  box-shadow: 0 25px 42px rgb(245 34 45 / 24%);
  clip-path: polygon(50% 0, 100% 18%, 88% 74%, 50% 100%, 12% 74%, 0 18%);
  font-size: 48px;
}

.orbit {
  position: absolute;
  width: 280px;
  height: 110px;
  border: 1px solid rgb(245 34 45 / 24%);
  border-radius: 50%;
}

.orbit.two {
  width: min(100vw - 48px, 360px);
  height: 150px;
  opacity: 0.5;
}

.introduction-content ul {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(96px, 1fr));
  gap: var(--space-3);
  padding: 0;
  margin: 24px 0 0;
  list-style: none;
}

.introduction-content li {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  align-items: center;
  text-align: center;
}

.introduction-content li > span {
  display: grid;
  width: 46px;
  height: 46px;
  margin-bottom: var(--space-2);
  place-items: center;
  color: var(--color-brand-600);
  background: white;
  border: 1px solid var(--color-neutral-200);
  border-radius: 50%;
}

.introduction-content li svg {
  width: 22px;
}

.introduction-content li small {
  color: var(--color-neutral-500);
}

.login-panel {
  display: grid;
  min-height: 100vh;
  padding: clamp(20px, 4vw, 48px);
  background:
    radial-gradient(circle at 85% 12%, rgb(47 94 158 / 4%), transparent 24%),
    #fbfcff;
  place-items: center;
}

.login-card {
  width: min(100%, 510px);
  padding: clamp(24px, 4vw, 54px) clamp(22px, 4vw, 58px) clamp(24px, 4vw, 46px);
  text-align: center;
  background: white;
  border: 1px solid #edf0f4;
  border-radius: 18px;
  box-shadow: 0 18px 50px rgb(18 28 42 / 9%);
}

.login-mark {
  margin: 0 auto 22px;
}

.login-card h2 {
  margin: 0;
  color: #172438;
  font-size: 34px;
}

.login-description {
  margin: var(--space-2) 0 0;
  color: var(--color-neutral-500);
  font-size: var(--font-size-lg);
}

.title-line {
  width: 42px;
  margin: 22px auto 28px;
}

.login-error {
  padding: var(--space-3);
  margin-bottom: var(--space-4);
  color: var(--color-danger-500);
  text-align: left;
  background: #fff2f2;
  border: 1px solid #ffd7d7;
  border-radius: var(--radius-md);
}

.form-field {
  display: grid;
  gap: var(--space-2);
  margin-bottom: var(--space-5);
  text-align: left;
}

.form-field label {
  font-weight: 700;
}

.field-error {
  color: var(--color-danger-500);
  font-size: var(--font-size-xs);
}

.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: -4px 0 var(--space-5);
}

.login-submit {
  width: 100%;
  height: 54px;
  font-size: var(--font-size-lg);
  font-weight: 700;
}

.security-note {
  margin: var(--space-6) 0 0;
  color: var(--color-neutral-500);
  font-size: var(--font-size-sm);
}

@media (width <= 980px) {
  .login-page {
    display: block;
  }

  .login-introduction {
    display: none;
  }

  .login-panel {
    padding: var(--space-5);
  }
}

@media (width <= 480px) {
  .login-options {
    align-items: flex-start;
    flex-direction: column;
    gap: 10px;
  }

  .login-card {
    padding: var(--space-6);
  }
}
</style>
