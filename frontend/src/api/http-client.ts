import { parseProblemDetails, toApiProblem } from "./problem-details";

export interface HttpClient {
  get(path: string): Promise<unknown>;
  post(path: string, body: unknown, options?: RequestOptions): Promise<unknown>;
  postForm(
    path: string,
    body: FormData,
    options?: RequestOptions,
  ): Promise<unknown>;
  patch(
    path: string,
    body: unknown,
    options?: RequestOptions,
  ): Promise<unknown>;
  put(path: string, body: unknown): Promise<unknown>;
  getBlob(path: string): Promise<Blob>;
  getBlobResponse(path: string): Promise<{ blob: Blob; fileName?: string }>;
  delete(path: string): Promise<void>;
  setUnauthorizedHandler(handler: () => void): void;
}

export interface RequestOptions {
  headers?: Record<string, string>;
}

interface HttpClientOptions {
  baseUrl?: string;
  fetchFn?: typeof fetch;
  readCookie?: (name: string) => string | undefined;
  timeoutMs: number;
}

function readBrowserCookie(name: string): string | undefined {
  if (typeof document === "undefined") {
    return undefined;
  }
  const prefix = `${encodeURIComponent(name)}=`;
  const value = document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix))
    ?.slice(prefix.length);
  return value === undefined ? undefined : decodeURIComponent(value);
}

function joinUrl(baseUrl: string, path: string): string {
  return `${baseUrl.replace(/\/$/, "")}/${path.replace(/^\//, "")}`;
}

export function createHttpClient(options: HttpClientOptions): HttpClient {
  const baseUrl = options.baseUrl?.trim() ?? "";
  const fetchFn = options.fetchFn ?? fetch;
  const readCookie = options.readCookie ?? readBrowserCookie;
  let handleUnauthorized: () => void = () => undefined;

  async function request(
    method: "GET" | "POST" | "PATCH" | "PUT" | "DELETE",
    path: string,
    body?: unknown | FormData,
    responseKind: "json" | "blob" = "json",
    requestOptions: RequestOptions = {},
  ) {
    const controller = new AbortController();
    const timeoutId = globalThis.setTimeout(
      () => controller.abort(),
      options.timeoutMs,
    );
    const headers = new Headers({ Accept: "application/json" });
    if (body !== undefined && !(body instanceof FormData)) {
      headers.set("Content-Type", "application/json");
    }
    if (method !== "GET") {
      const csrfToken = readCookie("XSRF-TOKEN");
      if (csrfToken !== undefined && csrfToken.length > 0) {
        headers.set("X-XSRF-TOKEN", csrfToken);
      }
    }
    for (const [name, value] of Object.entries(requestOptions.headers ?? {})) {
      headers.set(name, value);
    }

    try {
      const response = await fetchFn(joinUrl(baseUrl, path), {
        method,
        headers,
        credentials: "include",
        signal: controller.signal,
        ...(body === undefined
          ? {}
          : { body: body instanceof FormData ? body : JSON.stringify(body) }),
      });

      const contentType = response.headers.get("Content-Type") ?? "";
      let payload: unknown;
      if (response.status !== 204 && contentType.includes("json")) {
        try {
          payload = await response.json();
        } catch {
          payload = undefined;
        }
      }

      if (!response.ok) {
        const problem = parseProblemDetails(payload, {
          status: response.status,
          title: response.statusText || "请求未能完成",
          instance: path,
        });
        if (response.status === 401) {
          handleUnauthorized();
        }
        throw problem;
      }
      if (responseKind === "blob") {
        const blob = await response.blob();
        return {
          blob,
          fileName: parseContentDispositionFileName(
            response.headers.get("Content-Disposition"),
          ),
        };
      }
      return payload;
    } catch (error) {
      throw toApiProblem(error);
    } finally {
      globalThis.clearTimeout(timeoutId);
    }
  }

  return {
    get: (path) => request("GET", path),
    post: (path, body, options) => request("POST", path, body, "json", options),
    postForm: (path, body, options) =>
      request("POST", path, body, "json", options),
    patch: (path, body, options) =>
      request("PATCH", path, body, "json", options),
    put: (path, body) => request("PUT", path, body),
    getBlob: async (path) =>
      (
        (await request("GET", path, undefined, "blob")) as {
          blob: Blob;
          fileName?: string;
        }
      ).blob,
    getBlobResponse: (path) =>
      request("GET", path, undefined, "blob") as Promise<{
        blob: Blob;
        fileName?: string;
      }>,
    delete: async (path) => {
      await request("DELETE", path);
    },
    setUnauthorizedHandler: (handler) => {
      handleUnauthorized = handler;
    },
  };
}

function parseContentDispositionFileName(value: string | null): string | undefined {
  if (!value) return undefined;
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(value)?.[1];
  if (encoded) {
    try {
      return decodeURIComponent(encoded);
    } catch {
      return encoded;
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(value)?.[1];
  return plain;
}
