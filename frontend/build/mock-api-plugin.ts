import { randomUUID } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";

import type { Plugin } from "vite";

type UserRole = "SUPER_ADMIN" | "CITY_USER";

interface City {
  code: string;
  name: string;
}

interface User {
  id: string;
  username: string;
  displayName: string;
  roles: UserRole[];
  city: City | null;
  mustChangePassword: boolean;
}

interface CurrentSession {
  user: User;
}

const CSRF_TOKEN = "development-csrf-token";
const API_PREFIX = "/api/v1";

const cities: City[] = [
  { code: "320100", name: "南京市" },
  { code: "320200", name: "无锡市" },
  { code: "320300", name: "徐州市" },
  { code: "320400", name: "常州市" },
  { code: "320500", name: "苏州市" },
  { code: "320600", name: "南通市" },
  { code: "320700", name: "连云港市" },
  { code: "320800", name: "淮安市" },
  { code: "320900", name: "盐城市" },
  { code: "321000", name: "扬州市" },
  { code: "321100", name: "镇江市" },
  { code: "321200", name: "泰州市" },
  { code: "321300", name: "宿迁市" },
];

const cityUsernames = [
  "nanjing_user",
  "wuxi_user",
  "xuzhou_user",
  "changzhou_user",
  "suzhou_user",
  "nantong_user",
  "lianyungang_user",
  "huaian_user",
  "yancheng_user",
  "yangzhou_user",
  "zhenjiang_user",
  "taizhou_user",
  "suqian_user",
] as const;

const users: User[] = [
  {
    id: "1",
    username: "admin",
    displayName: "系统管理员",
    roles: ["SUPER_ADMIN"],
    city: null,
    mustChangePassword: false,
  },
  ...cities.map((city, index): User => ({
    id: String(index + 2),
    username: cityUsernames[index] ?? `city_${city.code}`,
    displayName: `${city.name}用户`,
    roles: ["CITY_USER"],
    city,
    mustChangePassword: true,
  })),
];

type Next = (error?: unknown) => void;

function parseCookies(header: string | undefined): ReadonlyMap<string, string> {
  const values = new Map<string, string>();
  for (const item of header?.split(";") ?? []) {
    const separator = item.indexOf("=");
    if (separator <= 0) continue;
    const key = item.slice(0, separator).trim();
    const value = item.slice(separator + 1).trim();
    values.set(key, decodeURIComponent(value));
  }
  return values;
}

function appendCookie(response: ServerResponse, cookie: string): void {
  const current = response.getHeader("Set-Cookie");
  const values = Array.isArray(current)
    ? current.map(String)
    : current === undefined
      ? []
      : [String(current)];
  response.setHeader("Set-Cookie", [...values, cookie]);
}

function setCsrfCookie(response: ServerResponse): void {
  appendCookie(response, `XSRF-TOKEN=${CSRF_TOKEN}; Path=/; SameSite=Lax`);
}

function sendJson(
  response: ServerResponse,
  status: number,
  payload: unknown,
): void {
  response.statusCode = status;
  response.setHeader("Cache-Control", "no-store");
  response.setHeader("Content-Type", "application/json; charset=utf-8");
  response.end(JSON.stringify(payload));
}

function sendProblem(
  response: ServerResponse,
  status: number,
  code: string,
  title: string,
  instance: string,
): void {
  response.statusCode = status;
  response.setHeader("Cache-Control", "no-store");
  response.setHeader("Content-Type", "application/problem+json; charset=utf-8");
  response.end(
    JSON.stringify({
      type: `https://three-fees.example/problems/${code.toLowerCase().replaceAll("_", "-")}`,
      title,
      status,
      detail: status === 401 ? "账号或口令不正确，或当前会话已失效。" : title,
      instance,
      code,
      traceId: `mock-${randomUUID()}`,
      fieldErrors: [],
    }),
  );
}

async function readJson(request: IncomingMessage): Promise<unknown> {
  let body = "";
  for await (const chunk of request) {
    body += String(chunk);
    if (body.length > 16_384) {
      throw new Error("Mock request body is too large");
    }
  }
  return JSON.parse(body);
}

function isLoginRequest(
  value: unknown,
): value is { username: string; password: string } {
  return (
    typeof value === "object" &&
    value !== null &&
    "username" in value &&
    typeof value.username === "string" &&
    "password" in value &&
    typeof value.password === "string"
  );
}

export function mockApiPlugin(): Plugin {
  const sessions = new Map<string, CurrentSession>();
  const middleware = async (
    request: IncomingMessage,
    response: ServerResponse,
    next: Next,
  ): Promise<void> => {
    const url = new URL(request.url ?? "/", "http://127.0.0.1");
    if (!url.pathname.startsWith(API_PREFIX)) {
      next();
      return;
    }

    const method = request.method ?? "GET";
    const cookies = parseCookies(request.headers.cookie);
    const sessionId = cookies.get("THREE_FEES_SESSION");
    const currentSession =
      sessionId === undefined ? undefined : sessions.get(sessionId);
    const path = url.pathname;

    if (method === "GET" && path === `${API_PREFIX}/sessions/current`) {
      setCsrfCookie(response);
      if (currentSession === undefined) {
        sendProblem(response, 401, "AUTHENTICATION_REQUIRED", "请先登录", path);
        return;
      }
      sendJson(response, 200, currentSession);
      return;
    }

    if (method !== "GET" && request.headers["x-xsrf-token"] !== CSRF_TOKEN) {
      sendProblem(response, 403, "CSRF_TOKEN_INVALID", "安全校验失败", path);
      return;
    }

    if (method === "POST" && path === `${API_PREFIX}/sessions`) {
      let body: unknown;
      try {
        body = await readJson(request);
      } catch {
        sendProblem(
          response,
          422,
          "VALIDATION_FAILED",
          "请求参数校验失败",
          path,
        );
        return;
      }
      if (!isLoginRequest(body) || body.password.length < 6) {
        sendProblem(response, 401, "AUTHENTICATION_FAILED", "认证失败", path);
        return;
      }
      const user = users.find(
        (candidate) => candidate.username === body.username,
      );
      if (user === undefined) {
        sendProblem(response, 401, "AUTHENTICATION_FAILED", "认证失败", path);
        return;
      }

      const newSessionId = randomUUID();
      const newSession = { user };
      sessions.set(newSessionId, newSession);
      appendCookie(
        response,
        `THREE_FEES_SESSION=${newSessionId}; HttpOnly; Path=/; SameSite=Lax`,
      );
      response.setHeader("Location", `${API_PREFIX}/sessions/current`);
      sendJson(response, 201, newSession);
      return;
    }

    if (method === "DELETE" && path === `${API_PREFIX}/sessions/current`) {
      if (sessionId !== undefined) sessions.delete(sessionId);
      appendCookie(
        response,
        "THREE_FEES_SESSION=; HttpOnly; Max-Age=0; Path=/; SameSite=Lax",
      );
      response.statusCode = 204;
      response.setHeader("Cache-Control", "no-store");
      response.end();
      return;
    }

    if (currentSession === undefined) {
      sendProblem(response, 401, "AUTHENTICATION_REQUIRED", "请先登录", path);
      return;
    }

    if (method === "GET" && path === `${API_PREFIX}/dashboard/summary`) {
      sendJson(response, 200, {
        currentDataPeriod: null,
        cityCount: currentSession.user.roles.includes("SUPER_ADMIN") ? 13 : 1,
        billingPointCount: 0,
        overLimitBillingPointCount: 0,
        draftReportCount: 0,
      });
      return;
    }

    if (method === "GET" && path === `${API_PREFIX}/cities`) {
      sendJson(response, 200, cities);
      return;
    }

    if (method === "GET" && path === `${API_PREFIX}/users`) {
      if (!currentSession.user.roles.includes("SUPER_ADMIN")) {
        sendProblem(response, 403, "FORBIDDEN", "无权访问", path);
        return;
      }
      const page = Math.max(0, Number(url.searchParams.get("page") ?? 0) || 0);
      const requestedSize = Number(url.searchParams.get("size") ?? 20) || 20;
      const size = Math.min(100, Math.max(1, requestedSize));
      const offset = page * size;
      sendJson(response, 200, {
        items: users.slice(offset, offset + size),
        page,
        size,
        totalElements: users.length,
        totalPages: Math.ceil(users.length / size),
      });
      return;
    }

    sendProblem(response, 404, "RESOURCE_NOT_FOUND", "资源不存在", path);
  };

  return {
    name: "three-fees-development-mock-api",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use(middleware);
    },
    configurePreviewServer(server) {
      server.middlewares.use(middleware);
    },
  };
}
