import { flushPromises, mount } from "@vue/test-utils";
import type * as VueRouterModule from "vue-router";
import { describe, expect, it, vi } from "vitest";

const login = vi.fn();
const replace = vi.fn();

vi.mock("@/stores/session", () => ({
  useSessionStore: () => ({
    login,
  }),
}));

vi.mock("vue-router", async (importOriginal) => {
  const original = await importOriginal<typeof VueRouterModule>();
  return {
    ...original,
    useRoute: () => ({ query: {} }),
    useRouter: () => ({ replace }),
  };
});

import LoginView from "@/views/login/LoginView.vue";

describe("LoginView", () => {
  it("submits credentials and navigates to the dashboard", async () => {
    login.mockResolvedValue({ ok: true });
    const wrapper = mount(LoginView);

    await wrapper.get('[data-testid="username-input"]').setValue("admin");
    await wrapper.get('[data-testid="password-input"]').setValue("mock-value");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(login).toHaveBeenCalledWith({
      username: "admin",
      password: "mock-value",
    });
    expect(replace).toHaveBeenCalledWith("/dashboard");
  });

  it("shows a generic authentication error", async () => {
    login.mockResolvedValue({
      ok: false,
      problem: { code: "AUTHENTICATION_FAILED" },
    });
    const wrapper = mount(LoginView);

    await wrapper.get('[data-testid="username-input"]').setValue("unknown");
    await wrapper.get('[data-testid="password-input"]').setValue("mock-value");
    await wrapper.get("form").trigger("submit");
    await flushPromises();

    expect(wrapper.get('[role="alert"]').text()).toContain("账号或口令不正确");
  });
});
