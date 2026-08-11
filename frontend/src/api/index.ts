import { createHttpAppApi } from "./app-api";
import { createHttpClient } from "./http-client";

const configuredTimeout = Number(
  import.meta.env.VITE_API_TIMEOUT_MS ?? "10000",
);
const timeoutMs =
  Number.isFinite(configuredTimeout) && configuredTimeout > 0
    ? configuredTimeout
    : 10_000;

export const httpClient = createHttpClient({
  ...(import.meta.env.VITE_API_BASE_URL === undefined
    ? {}
    : { baseUrl: import.meta.env.VITE_API_BASE_URL }),
  timeoutMs,
});

export const api = createHttpAppApi(httpClient);

export type { AppApi } from "./app-api";
