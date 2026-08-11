import js from "@eslint/js";
import eslintConfigPrettier from "eslint-config-prettier";
import pluginVue from "eslint-plugin-vue";
import globals from "globals";
import tseslint from "typescript-eslint";

export default tseslint.config(
  {
    ignores: [
      "coverage/**",
      "dist/**",
      "node_modules/**",
      "playwright-report/**",
      "src/types/components.d.ts",
      "test-results/**",
    ],
  },
  {
    files: ["**/*.{js,mjs,ts,vue}"],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs["flat/recommended"],
  {
    files: ["src/**/*.{ts,vue}", "tests/unit/**/*.ts"],
    languageOptions: {
      globals: {
        ...globals.browser,
      },
      parserOptions: {
        parser: tseslint.parser,
      },
    },
    rules: {
      "@typescript-eslint/consistent-type-imports": [
        "error",
        { fixStyle: "inline-type-imports" },
      ],
      "@typescript-eslint/no-explicit-any": "error",
      "vue/block-lang": ["error", { script: { lang: "ts" } }],
      "vue/component-name-in-template-casing": ["error", "PascalCase"],
      "vue/multi-word-component-names": "error",
    },
  },
  {
    files: [
      "vite.config.ts",
      "playwright.config.ts",
      "build/**/*.ts",
      "tests/e2e/**/*.ts",
    ],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
  },
  eslintConfigPrettier,
);
