import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { api } from "@/api";
import type { CurrentSession } from "@/api/contracts";
import { useSessionStore } from "@/stores/session";

vi.mock("@/api", () => ({
  api: {
    sessions: {
      create: vi.fn(),
      current: vi.fn(),
      remove: vi.fn(),
    },
  },
}));

const adminSession: CurrentSession = {
  user: {
    id: "1",
    username: "admin",
    displayName: "系统管理员",
    roles: ["SUPER_ADMIN"],
    city: null,
    mustChangePassword: false,
  },
};

describe("session store", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it("stores the authenticated session after login", async () => {
    vi.mocked(api.sessions.create).mockResolvedValue(adminSession);
    const store = useSessionStore();

    const result = await store.login({
      username: "admin",
      password: "mock-value",
    });

    expect(result.ok).toBe(true);
    expect(store.isAuthenticated).toBe(true);
    expect(store.currentUser?.roles).toEqual(["SUPER_ADMIN"]);
  });

  it("returns a structured failure without exposing credentials", async () => {
    vi.mocked(api.sessions.create).mockRejectedValue(
      new Error("network unavailable"),
    );
    const store = useSessionStore();

    const result = await store.login({
      username: "admin",
      password: "not-recorded",
    });

    expect(result.ok).toBe(false);
    expect(store.isAuthenticated).toBe(false);
  });

  it("clears local session state after logout", async () => {
    vi.mocked(api.sessions.create).mockResolvedValue(adminSession);
    vi.mocked(api.sessions.remove).mockResolvedValue(undefined);
    const store = useSessionStore();
    await store.login({ username: "admin", password: "mock-value" });

    await store.logout();

    expect(store.currentUser).toBeNull();
    expect(store.status).toBe("anonymous");
  });
});
