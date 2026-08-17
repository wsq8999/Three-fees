import { fileURLToPath, URL } from "node:url";

import vue from "@vitejs/plugin-vue";
import { defineConfig, loadEnv } from "vite";
import Components from "unplugin-vue-components/vite";
import { ElementPlusResolver } from "unplugin-vue-components/resolvers";

import { mockApiPlugin } from "./build/mock-api-plugin";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const proxyTarget =
    env.VITE_API_PROXY_TARGET?.trim() ||
    (mode === "development" ? "http://127.0.0.1:8080" : undefined);

  return {
    plugins: [
      vue(),
      Components({
        dts: "src/types/components.d.ts",
        resolvers: [
          ElementPlusResolver({ importStyle: mode === "test" ? false : "css" }),
        ],
      }),
      ...(mode === "mock" || env.VITE_API_MODE === "mock"
        ? [mockApiPlugin()]
        : []),
    ],
    resolve: {
      alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url)),
      },
    },
    server: proxyTarget
      ? {
          proxy: {
            "/actuator": { target: proxyTarget, changeOrigin: true },
            "/api": { target: proxyTarget, changeOrigin: true },
          },
        }
      : {},
    preview: proxyTarget
      ? {
          proxy: {
            "/actuator": { target: proxyTarget, changeOrigin: true },
            "/api": { target: proxyTarget, changeOrigin: true },
          },
        }
      : {},
    test: {
      include: ["tests/unit/**/*.spec.ts"],
      environment: "jsdom",
      globals: true,
      setupFiles: ["./tests/unit/setup.ts"],
      css: true,
      coverage: {
        reporter: ["text", "html"],
      },
    },
  };
});
