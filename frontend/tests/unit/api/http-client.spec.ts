import { describe, expect, it, vi } from "vitest";

import { ApiProblem } from "@/api/problem-details";
import { createHttpClient } from "@/api/http-client";

describe("HTTP client", () => {
  it("sends same-origin cookies and the CSRF header for unsafe requests", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(JSON.stringify({ accepted: true }), {
        status: 201,
        headers: { "Content-Type": "application/json" },
      }),
    );
    const client = createHttpClient({
      fetchFn: fetchMock,
      readCookie: () => "csrf-value",
      timeoutMs: 1_000,
    });

    await client.post("/api/v1/sessions", {
      username: "admin",
      password: "mock-value",
    });

    expect(fetchMock).toHaveBeenCalledOnce();
    const [, request] = fetchMock.mock.calls[0] ?? [];
    expect(request?.credentials).toBe("include");
    expect(new Headers(request?.headers).get("X-XSRF-TOKEN")).toBe(
      "csrf-value",
    );
  });

  it("maps RFC 9457 responses to a stable ApiProblem", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(
        JSON.stringify({
          type: "https://three-fees.example/problems/forbidden",
          title: "无权访问",
          status: 403,
          detail: "当前账号不能访问此资源",
          instance: "/api/v1/users",
          code: "FORBIDDEN",
          traceId: "trace-test",
          fieldErrors: [],
        }),
        {
          status: 403,
          headers: { "Content-Type": "application/problem+json" },
        },
      ),
    );
    const client = createHttpClient({ fetchFn: fetchMock, timeoutMs: 1_000 });

    const rejection = client.get("/api/v1/users");

    await expect(rejection).rejects.toMatchObject({
      status: 403,
      code: "FORBIDDEN",
      traceId: "trace-test",
    });
  });

  it("notifies the session boundary after a 401 response", async () => {
    const handleUnauthorized = vi.fn();
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        new Response(null, { status: 401, statusText: "Unauthorized" }),
      );
    const client = createHttpClient({ fetchFn: fetchMock, timeoutMs: 1_000 });
    client.setUnauthorizedHandler(handleUnauthorized);

    await expect(client.get("/api/v1/sessions/current")).rejects.toBeInstanceOf(
      ApiProblem,
    );
    expect(handleUnauthorized).toHaveBeenCalledOnce();
  });
});
