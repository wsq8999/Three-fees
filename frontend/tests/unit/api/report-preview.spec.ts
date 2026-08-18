import { describe, expect, it } from "vitest";

import { buildReportPreviewHtml } from "@/api/business-api";

describe("confirmed report preview", () => {
  it("renders the same complete confirmed sections represented in the generated Word", () => {
    const html = buildReportPreviewHtml({
      title: "最终确认报告",
      situation: "最终情况说明",
      analysis: "<p>最终排查分析</p>",
      rectification: "最终整改小结",
    });

    expect(html).toContain("最终确认报告");
    expect(html).toContain("一、情况说明");
    expect(html).toContain("最终情况说明");
    expect(html).toContain("二、排查分析");
    expect(html).toContain("最终排查分析");
    expect(html).toContain("三、整改小结");
    expect(html).toContain("最终整改小结");
  });

  it("keeps an imported full-document preview intact", () => {
    const imported = "<article><h1>原始历史报告</h1><p>完整正文</p></article>";

    expect(
      buildReportPreviewHtml({
        title: "历史报告",
        situation: imported,
        analysis: "",
        rectification: "",
      }),
    ).toBe(imported);
  });
});
