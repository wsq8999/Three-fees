import { computed, shallowRef } from "vue";
import { defineStore } from "pinia";

import { api } from "@/api";
import type {
  CreateSessionRequest,
  CurrentSession,
  UserRole,
} from "@/api/contracts";
import { type ApiProblem, toApiProblem } from "@/api/problem-details";

type SessionStatus =
  "idle" | "loading" | "authenticated" | "anonymous" | "error";
type SessionActionResult = { ok: true } | { ok: false; problem: ApiProblem };

export const useSessionStore = defineStore("session", () => {
  const currentSession = shallowRef<CurrentSession | null>(null);
  const status = shallowRef<SessionStatus>("idle");
  const lastProblem = shallowRef<ApiProblem | null>(null);

  const currentUser = computed(() => currentSession.value?.user ?? null);
  const isAuthenticated = computed(() => status.value === "authenticated");

  function markAnonymous(): void {
    currentSession.value = null;
    status.value = "anonymous";
  }

  function hasRole(role: UserRole): boolean {
    return currentUser.value?.roles.includes(role) ?? false;
  }

  async function restore(): Promise<SessionActionResult> {
    if (status.value === "authenticated") {
      return { ok: true };
    }
    status.value = "loading";
    lastProblem.value = null;
    try {
      currentSession.value = await api.sessions.current();
      status.value = "authenticated";
      return { ok: true };
    } catch (error) {
      const problem = toApiProblem(error);
      lastProblem.value = problem;
      if (problem.status === 401) {
        markAnonymous();
      } else {
        currentSession.value = null;
        status.value = "error";
      }
      return { ok: false, problem };
    }
  }

  async function login(
    request: CreateSessionRequest,
  ): Promise<SessionActionResult> {
    status.value = "loading";
    lastProblem.value = null;
    try {
      currentSession.value = await api.sessions.create(request);
      status.value = "authenticated";
      return { ok: true };
    } catch (error) {
      const problem = toApiProblem(error);
      currentSession.value = null;
      status.value = "anonymous";
      lastProblem.value = problem;
      return { ok: false, problem };
    }
  }

  async function logout(): Promise<SessionActionResult> {
    try {
      await api.sessions.remove();
      return { ok: true };
    } catch (error) {
      return { ok: false, problem: toApiProblem(error) };
    } finally {
      markAnonymous();
    }
  }

  return {
    currentSession,
    currentUser,
    hasRole,
    isAuthenticated,
    lastProblem,
    login,
    logout,
    markAnonymous,
    restore,
    status,
  };
});
