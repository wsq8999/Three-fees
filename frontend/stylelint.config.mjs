export default {
  extends: ["stylelint-config-standard", "stylelint-config-recommended-vue"],
  ignoreFiles: ["dist/**", "node_modules/**", "coverage/**"],
  rules: {
    "color-function-notation": "modern",
    "custom-property-empty-line-before": null,
    "font-family-name-quotes": null,
    "no-descending-specificity": null,
    "rule-empty-line-before": null,
    "selector-class-pattern": [
      "^(?:[a-z][a-z0-9]*(?:-[a-z0-9]+)*|el-[a-z0-9-]+(?:__[a-z0-9-]+)?|is-[a-z0-9-]+)$",
      {
        message: "CSS 类名必须使用 kebab-case",
      },
    ],
  },
};
