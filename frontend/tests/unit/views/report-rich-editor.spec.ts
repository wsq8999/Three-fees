import { describe, expect, it } from "vitest";

import {
  draftToEditorSections,
  draftToEditorHtml,
  editorHtmlToSections,
  imageFileIdsFromHtml,
  sanitizeReportHtml,
  sectionEditorHtmlToBlocks,
} from "@/views/reports/report-rich-editor";
import type { ReportDraft } from "@/types/business";

function draftWithSections(
  title: string,
  situation: string,
  analysis: string,
  rectification: string,
): ReportDraft {
  return {
    id: "draft-1",
    billingPointId: "snapshot-1",
    billingPointCode: "BP-1",
    billingPointName: "测试报账点",
    city: { code: "320100", name: "南京市" },
    period: "2026-06",
    status: "EDITING",
    analysisStatus: "PENDING_ANALYSIS",
    analysisTaskId: null,
    analysisErrorCode: null,
    analysisSubmittedAt: null,
    analysisCompletedAt: null,
    blocks: [
      { id: "title", type: "HEADING", title: "报告标题", content: title },
      { id: "situation", type: "SITUATION", title: "情况说明", content: situation },
      { id: "analysis", type: "ANALYSIS", title: "审计分析", content: analysis },
      {
        id: "rectification",
        type: "RECTIFICATION",
        title: "整改建议",
        content: rectification,
      },
    ],
    imageFileIds: [],
    messages: [],
    currentVersion: 0,
    updatedAt: "2026-08-26T00:00:00",
    formalReportId: null,
    entityVersion: 1,
  };
}

describe("report rich editor helpers", () => {
  it("builds one editable report document from draft sections", () => {
    const html = draftToEditorHtml(
      draftWithSections("稽核报告", "情况正文", "<p>分析正文</p>", "整改正文"),
    );

    expect(html).toContain("<h1>稽核报告</h1>");
    expect(html).toContain("<h2>一、情况说明</h2>");
    expect(html).toContain("<p>情况正文</p>");
    expect(html).toContain("<p>分析正文</p>");
    expect(html).toContain("<h2>三、整改小结</h2>");
  });

  it("parses edited report html back to backend sections", () => {
    const sections = editorHtmlToSections(
      '<article><h1>最终报告</h1><h2>一、情况说明</h2><p>情况</p><h2>二、排查分析</h2><p>分析</p><h2>三、整改小结</h2><p>整改</p></article>',
    );

    expect(sections.title).toBe("最终报告");
    expect(sections.situation).toBe("<p>情况</p>");
    expect(sections.analysis).toBe("<p>分析</p>");
    expect(sections.rectification).toBe("<p>整改</p>");
  });

  it("keeps fixed title and section headings outside editable section content", () => {
    const draft = draftWithSections(
      "固定标题",
      "<p>情况</p>",
      '<p>分析</p><figure class="inline-report-image" data-file-id="img-1" data-display-width="220" data-display-height="110"><img data-file-id="img-1" /></figure>',
      '<div class="inline-image-row" data-image-group-id="group-1"><figure data-file-id="img-2"><img data-file-id="img-2" /></figure><figure data-file-id="img-3"><img data-file-id="img-3" /></figure></div><p>整改</p>',
    );
    const sections = draftToEditorSections(draft);
    const blocks = sectionEditorHtmlToBlocks(sections, draft.blocks);
    const visibleIds = imageFileIdsFromHtml(
      `${sections.situation}${sections.analysis}${sections.rectification}`,
    );

    expect(sections.title).toBe("固定标题");
    expect(sections.situation).toBe("<p>情况</p>");
    expect(blocks.find((block) => block.type === "HEADING")?.content).toBe(
      "固定标题",
    );
    expect(blocks.find((block) => block.type === "SITUATION")?.content).not.toContain(
      "一、情况说明",
    );
    expect(visibleIds).toEqual(["img-1", "img-2", "img-3"]);
  });

  it("keeps formal image and image group nodes while removing editor chrome", () => {
    const html = sanitizeReportHtml(
      '<div class="inline-image-row" data-image-group-id="group-1">' +
        '<figure class="inline-report-image is-selected" contenteditable="false" data-file-id="img-1" data-display-width="220" data-display-height="110">' +
        '<img data-file-id="img-1" width="220" height="110" /><span class="inline-image-resize-handle se"></span></figure>' +
        '<figure class="inline-report-image" data-file-id="img-2" data-display-width="180" data-display-height="90"><img data-file-id="img-2" /></figure>' +
        "</div>",
    );

    expect(html).toContain('class="inline-image-row"');
    expect(html).toContain('data-image-group-id="group-1"');
    expect(html).toContain('data-file-id="img-1"');
    expect(html).toContain('data-display-width="220"');
    expect(html).toContain('data-file-id="img-2"');
    expect(html).not.toContain("inline-image-resize-handle");
    expect(html).not.toContain("contenteditable");
  });

  it("normalizes inline editor images to formal single image or image row nodes", () => {
    const single = sanitizeReportHtml(
      '<p><span class="inline-report-image" data-file-id="img-1" data-display-width="320" data-display-height="180"><img data-file-id="img-1" /></span></p>',
    );
    const row = sanitizeReportHtml(
      '<p><span class="inline-report-image" data-file-id="img-2"><img data-file-id="img-2" /></span><span class="inline-report-image" data-file-id="img-3"><img data-file-id="img-3" /></span></p>',
    );

    expect(single).toContain("<figure");
    expect(single).toContain('class="inline-report-image"');
    expect(single).not.toContain("<p>");
    expect(row).toContain('class="inline-image-row"');
    expect(row).toContain('data-file-id="img-2"');
    expect(row).toContain('data-file-id="img-3"');
  });

  it("splits image-only paragraphs into separate saved rows at hard breaks", () => {
    const html = sanitizeReportHtml(
      '<p><span class="inline-report-image" data-file-id="img-1"><img data-file-id="img-1" /></span><span class="inline-report-image" data-file-id="img-2"><img data-file-id="img-2" /></span><br><span class="inline-report-image" data-file-id="img-3"><img data-file-id="img-3" /></span></p>',
    );

    expect(html).toContain('class="inline-image-row"');
    expect(html.indexOf('data-file-id="img-1"')).toBeLessThan(
      html.indexOf('data-file-id="img-2"'),
    );
    expect(html.indexOf('data-file-id="img-2"')).toBeLessThan(
      html.indexOf('data-file-id="img-3"'),
    );
    expect(html).not.toContain("<p>");
  });

  it("extracts image ids in document order with grouped images preserved", () => {
    const ids = imageFileIdsFromHtml(
      '<p>前文</p><div class="inline-image-row"><figure data-file-id="img-2"><img data-file-id="img-2" /></figure><figure data-file-id="img-1"><img data-file-id="img-1" /></figure></div><figure data-file-id="img-3"><img /></figure>',
    );

    expect(ids).toEqual(["img-2", "img-1", "img-3"]);
  });
});
