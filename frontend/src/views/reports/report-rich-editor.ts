import { Node, mergeAttributes } from "@tiptap/core";
import Image from "@tiptap/extension-image";

import type { DraftBlock, ReportDraft } from "@/types/business";

export type InlineImageSize = {
  width: number;
  height: number;
};

export type RichEditorSections = {
  title: string;
  situation: string;
  analysis: string;
  rectification: string;
};

export type RichEditorSectionKey = Exclude<keyof RichEditorSections, "title">;

const SECTION_HEADINGS = [
  { key: "situation", marker: "一", title: "情况说明" },
  { key: "analysis", marker: "二", title: "排查分析" },
  { key: "rectification", marker: "三", title: "整改小结" },
] as const;

export const ReportImage = Image.extend({
  name: "reportImage",

  addAttributes() {
    return {
      fileId: {
        default: null,
        parseHTML: (element) =>
          element.getAttribute("data-file-id") ??
          element.closest("[data-file-id]")?.getAttribute("data-file-id"),
        renderHTML: (attributes) =>
          attributes.fileId ? { "data-file-id": attributes.fileId } : {},
      },
      src: {
        default: null,
      },
      alt: {
        default: "稽核证据图片",
      },
      displayWidth: {
        default: null,
        parseHTML: (element) =>
          element.getAttribute("data-display-width") ??
          element.getAttribute("width") ??
          cssValue(element.getAttribute("style"), "width"),
      },
      displayHeight: {
        default: null,
        parseHTML: (element) =>
          element.getAttribute("data-display-height") ??
          element.getAttribute("height") ??
          cssValue(element.getAttribute("style"), "height"),
      },
    };
  },

  renderHTML({ HTMLAttributes }) {
    const fileId = stringAttr(HTMLAttributes.fileId);
    const width = numberAttr(HTMLAttributes.displayWidth);
    const height = numberAttr(HTMLAttributes.displayHeight);
    const imgAttrs: Record<string, string> = {
      src: stringAttr(HTMLAttributes.src) || imageUrl(fileId),
      alt: stringAttr(HTMLAttributes.alt) || "稽核证据图片",
    };
    if (fileId) imgAttrs["data-file-id"] = fileId;
    if (width !== null) {
      imgAttrs["data-display-width"] = String(width);
      imgAttrs.width = String(Math.round(width));
      imgAttrs.style = `width:${width}px;`;
    }
    if (height !== null) {
      imgAttrs["data-display-height"] = String(height);
      imgAttrs.height = String(Math.round(height));
      imgAttrs.style = `${imgAttrs.style ?? ""}height:${height}px;`;
    }
    return ["img", mergeAttributes(this.options.HTMLAttributes, imgAttrs)];
  },
});

export const ReportImageFigure = Node.create({
  name: "reportImageFigure",
  group: "inline",
  inline: true,
  atom: true,
  draggable: true,
  selectable: true,

  addAttributes() {
    return {
      fileId: {
        default: null,
        parseHTML: (element) => element.getAttribute("data-file-id"),
      },
      displayWidth: {
        default: null,
        parseHTML: (element) => element.getAttribute("data-display-width"),
      },
      displayHeight: {
        default: null,
        parseHTML: (element) => element.getAttribute("data-display-height"),
      },
      alt: {
        default: "稽核证据图片",
        parseHTML: (element) =>
          element.getAttribute("alt") ??
          element.querySelector("img")?.getAttribute("alt") ??
          "稽核证据图片",
      },
    };
  },

  parseHTML() {
    return [
      { tag: "img.inline-report-image[data-file-id]" },
      { tag: "span.inline-report-image[data-file-id]" },
      { tag: "figure.inline-report-image[data-file-id]" },
    ];
  },

  renderHTML({ HTMLAttributes }) {
    const fileId = stringAttr(HTMLAttributes.fileId);
    const width = numberAttr(HTMLAttributes.displayWidth);
    const height = numberAttr(HTMLAttributes.displayHeight);
    const imgAttrs: Record<string, string> = {
      class: "inline-report-image",
      "data-file-id": fileId,
      src: imageUrl(fileId),
      alt: stringAttr(HTMLAttributes.alt) || "稽核证据图片",
      draggable: "false",
    };
    if (width !== null) {
      imgAttrs["data-display-width"] = String(width);
      imgAttrs.width = String(Math.round(width));
      imgAttrs.style = `width:${width}px;`;
    }
    if (height !== null) {
      imgAttrs["data-display-height"] = String(height);
      imgAttrs.height = String(Math.round(height));
      imgAttrs.style = `${imgAttrs.style ?? ""}height:${height}px;`;
    }
    return ["img", imgAttrs];
  },
});

export const ReportImageGroup = Node.create({
  name: "reportImageGroup",
  group: "block",
  content: "reportImageFigure+",
  isolating: true,
  draggable: true,

  addAttributes() {
    return {
      groupId: {
        default: null,
        parseHTML: (element) => element.getAttribute("data-image-group-id"),
      },
    };
  },

  parseHTML() {
    return [{ tag: "div.inline-image-row" }];
  },

  renderHTML({ HTMLAttributes }) {
    return [
      "div",
      {
        class: "inline-image-row",
        "data-image-group-id":
          stringAttr(HTMLAttributes.groupId) || randomId("image-group"),
      },
      0,
    ];
  },
});

export function draftToEditorHtml(draft: ReportDraft): string {
  const sections = draftToEditorSections(draft);

  return sanitizeReportHtml(
    `<article class="confirmed-report-content"><h1>${escapeHtml(cleanText(sections.title))}</h1>` +
      sectionHtml("一、情况说明", sections.situation) +
      sectionHtml("二、排查分析", sections.analysis) +
      sectionHtml("三、整改小结", sections.rectification) +
      "</article>",
  );
}

export function draftToEditorSections(draft: ReportDraft): RichEditorSections {
  const blocks = draft.blocks;
  const title = blockContent(blocks, "HEADING") || "电费稽核报告";
  const situation = blockContent(blocks, "SITUATION");
  const analysis = blockContent(blocks, "ANALYSIS");
  const rectification = blockContent(blocks, "RECTIFICATION");

  if (looksLikeFullDocument(situation) && !analysis.trim() && !rectification.trim()) {
    const sections = editorHtmlToSections(situation);
    return {
      title: sections.title || title,
      situation: sectionEditorContentHtml(sections.situation),
      analysis: sectionEditorContentHtml(sections.analysis),
      rectification: sectionEditorContentHtml(sections.rectification),
    };
  }

  return {
    title: cleanText(title) || "电费稽核报告",
    situation: sectionEditorContentHtml(situation),
    analysis: sectionEditorContentHtml(analysis),
    rectification: sectionEditorContentHtml(rectification),
  };
}

export function sectionEditorHtmlToBlocks(
  sections: RichEditorSections,
  previousBlocks: DraftBlock[],
): DraftBlock[] {
  return previousBlocks.map((block) => {
    if (block.type === "HEADING") return { ...block, content: sections.title };
    if (block.type === "SITUATION") return { ...block, content: sections.situation };
    if (block.type === "ANALYSIS") return { ...block, content: sections.analysis };
    if (block.type === "RECTIFICATION") {
      return { ...block, content: sections.rectification };
    }
    return block;
  });
}

export function editorHtmlToBlocks(
  html: string,
  previousBlocks: DraftBlock[],
): DraftBlock[] {
  const sections = editorHtmlToSections(html);
  return previousBlocks.map((block) => {
    if (block.type === "HEADING") return { ...block, content: sections.title };
    if (block.type === "SITUATION") return { ...block, content: sections.situation };
    if (block.type === "ANALYSIS") return { ...block, content: sections.analysis };
    if (block.type === "RECTIFICATION") {
      return { ...block, content: sections.rectification };
    }
    return block;
  });
}

export function editorHtmlToSections(html: string): RichEditorSections {
  const template = document.createElement("template");
  template.innerHTML = sanitizeReportHtml(html);
  const root = template.content;
  normalizeImageGroups(root);
  const titleElement = root.querySelector("h1");
  const title = cleanText(titleElement?.textContent ?? "电费稽核报告");
  const result: RichEditorSections = {
    title,
    situation: "",
    analysis: "",
    rectification: "",
  };
  const headings = Array.from(root.querySelectorAll("h2"));
  for (const section of SECTION_HEADINGS) {
    const heading = headings.find((item) =>
      cleanText(item.textContent ?? "").replace(/[、.．\s]/g, "").startsWith(
        `${section.marker}${section.title}`,
      ),
    );
    if (heading === undefined) continue;
    result[section.key] = siblingsUntilNextHeading(heading);
  }
  if (!result.situation && !result.analysis && !result.rectification) {
    result.situation = sanitizeReportHtml(html);
  }
  return result;
}

export function sanitizeReportHtml(html: string): string {
  const template = document.createElement("template");
  template.innerHTML = decodeEscapedLineBreaks(html);
  template.content
    .querySelectorAll(
      'script,style,iframe,object,embed,link,meta,[data-inline-delete-caret="true"],.inline-image-resize-handle,.inline-image-drop-marker,.inline-image-uploading',
    )
    .forEach((element) => element.remove());
  template.content.querySelectorAll<HTMLElement>("*").forEach((element) => {
    Array.from(element.attributes).forEach((attribute) => {
      if (attribute.name.toLowerCase().startsWith("on")) {
        element.removeAttribute(attribute.name);
      }
    });
    element.removeAttribute("contenteditable");
    for (const attributeName of ["src", "href"]) {
      const value = element.getAttribute(attributeName);
      if (value?.trim().toLowerCase().startsWith("javascript:")) {
        element.removeAttribute(attributeName);
      }
    }
  });
  normalizeImageGroups(template.content);
  normalizeImageOnlyParagraphs(template.content);
  return template.innerHTML.trim();
}

export function imageFileIdsFromHtml(html: string): string[] {
  const template = document.createElement("template");
  template.innerHTML = html;
  const ids = Array.from(
    template.content.querySelectorAll<HTMLElement>(
      "figure[data-file-id], img[data-file-id]",
    ),
  )
    .map((element) => element.dataset.fileId)
    .filter((value): value is string => value !== undefined && value.length > 0);
  return Array.from(new Set(ids));
}

export function imageUrl(fileId: string): string {
  return `/api/v1/files/${encodeURIComponent(fileId)}?inline=true`;
}

export function randomId(prefix: string): string {
  return globalThis.crypto?.randomUUID?.() ?? `${prefix}-${Date.now()}`;
}

function blockContent(blocks: DraftBlock[], type: DraftBlock["type"]): string {
  return blocks.find((block) => block.type === type)?.content ?? "";
}

function sectionHtml(title: string, content: string): string {
  return `<h2>${title}</h2>${contentHtml(content)}`;
}

function contentHtml(content: string): string {
  const stripped = stripLeadingKnownHeading(content);
  if (looksLikeHtml(stripped)) return stripped;
  return stripped
    .split(/\r?\n/)
    .filter((line) => line.trim().length > 0)
    .map((line) => `<p>${escapeHtml(line.trim())}</p>`)
    .join("");
}

function sectionEditorContentHtml(content: string): string {
  const html = contentHtml(content);
  const template = document.createElement("template");
  template.innerHTML = sanitizeReportHtml(html);
  template.content
    .querySelectorAll<HTMLElement>(".inline-image-row")
    .forEach((row) => {
      const paragraph = document.createElement("p");
      Array.from(
        row.querySelectorAll<HTMLElement>("figure.inline-report-image[data-file-id]"),
      ).forEach((figure) => paragraph.append(editorImageElement(figure)));
      row.replaceWith(paragraph);
    });
  template.content
    .querySelectorAll<HTMLElement>("figure.inline-report-image[data-file-id]")
    .forEach((figure) => {
      const paragraph = document.createElement("p");
      paragraph.append(editorImageElement(figure));
      figure.replaceWith(paragraph);
    });
  return template.innerHTML.trim();
}

function editorImageElement(figure: HTMLElement): HTMLElement {
  const image =
    figure.querySelector<HTMLImageElement>("img") ?? document.createElement("img");
  const result = document.createElement("img");
  Array.from(figure.attributes).forEach((attribute) => {
    result.setAttribute(attribute.name, attribute.value);
  });
  Array.from(image.attributes).forEach((attribute) => {
    result.setAttribute(attribute.name, attribute.value);
  });
  result.className = "inline-report-image";
  result.dataset.fileId = figure.dataset.fileId ?? image.dataset.fileId ?? "";
  result.src = imageUrl(result.dataset.fileId);
  result.alt ||= "稽核证据图片";
  result.draggable = false;
  return result;
}

function stripLeadingKnownHeading(content: string): string {
  let next = content.trim();
  for (const section of SECTION_HEADINGS) {
    next = next.replace(
      new RegExp(
        `^\\s*(?:<h2[^>]*>)?${section.marker}\\s*[、.．]?\\s*${section.title}\\s*[：:]?\\s*(?:</h2>)?`,
        "i",
      ),
      "",
    );
  }
  return next.trim();
}

function siblingsUntilNextHeading(heading: Element): string {
  const nodes: string[] = [];
  let node = heading.nextSibling;
  while (node !== null) {
    if (node instanceof HTMLHeadingElement && node.tagName.toLowerCase() === "h2") {
      break;
    }
    if (node instanceof HTMLElement) {
      nodes.push(node.outerHTML);
    } else if (node instanceof Text && node.data.trim()) {
      nodes.push(`<p>${escapeHtml(node.data.trim())}</p>`);
    }
    node = node.nextSibling;
  }
  return nodes.join("").trim();
}

function normalizeImageGroups(root: DocumentFragment): void {
  root.querySelectorAll<HTMLElement>(".inline-report-image[data-file-id]").forEach((node) => {
    if (node.tagName.toLowerCase() !== "figure") replaceWithFigure(node);
  });
  root.querySelectorAll<HTMLElement>(".inline-image-row").forEach((row) => {
    row.dataset.imageGroupId ||= randomId("image-group");
    row.className = "inline-image-row";
    row.querySelectorAll<HTMLElement>("figure[data-file-id]").forEach((figure) => {
      normalizeFigure(figure);
    });
  });
  root.querySelectorAll<HTMLElement>("figure[data-file-id]").forEach((figure) => {
    if (figure.closest(".inline-image-row") === null) normalizeFigure(figure);
  });
}

function normalizeImageOnlyParagraphs(root: DocumentFragment): void {
  root.querySelectorAll<HTMLElement>("p").forEach((paragraph) => {
    const rows = imageRowsFromParagraph(paragraph);
    if (rows.length === 0) {
      return;
    }
    paragraph.replaceWith(...rows.map(imageRowElement));
  });
}

function imageRowsFromParagraph(paragraph: HTMLElement): HTMLElement[][] {
  const rows: HTMLElement[][] = [];
  let current: HTMLElement[] = [];
  for (const node of Array.from(paragraph.childNodes)) {
    if (node instanceof HTMLBRElement) {
      if (current.length > 0) rows.push(current);
      current = [];
      continue;
    }
    if (
      node instanceof HTMLElement &&
      node.matches("figure.inline-report-image[data-file-id]")
    ) {
      current.push(node);
      continue;
    }
    if ((node.textContent ?? "").trim().length > 0) {
      return [];
    }
  }
  if (current.length > 0) rows.push(current);
  return rows;
}

function imageRowElement(figures: HTMLElement[]): HTMLElement {
  if (figures.length === 1) return figures[0]!;
  const row = document.createElement("div");
  row.className = "inline-image-row";
  row.dataset.imageGroupId = randomId("image-group");
  figures.forEach((figure) => row.append(figure));
  return row;
}

function replaceWithFigure(node: HTMLElement): void {
  const figure = document.createElement("figure");
  Array.from(node.attributes).forEach((attribute) => {
    figure.setAttribute(attribute.name, attribute.value);
  });
  figure.className = "inline-report-image";
  if (node instanceof HTMLImageElement) {
    node.className = "";
    figure.append(node.cloneNode(false));
    node.replaceWith(figure);
    return;
  }
  while (node.firstChild !== null) figure.append(node.firstChild);
  node.replaceWith(figure);
}

function normalizeFigure(figure: HTMLElement): void {
  const fileId = figure.dataset.fileId ?? "";
  figure.className = "inline-report-image";
  figure.setAttribute("draggable", "true");
  const image =
    figure.querySelector<HTMLImageElement>("img") ?? document.createElement("img");
  if (image.parentElement !== figure) figure.append(image);
  image.dataset.fileId = fileId;
  image.src = imageUrl(fileId);
  image.alt ||= "稽核证据图片";
  image.draggable = false;
  const width =
    numberAttr(figure.dataset.displayWidth) ??
    numberAttr(image.dataset.displayWidth) ??
    numberAttr(image.getAttribute("width"));
  const height =
    numberAttr(figure.dataset.displayHeight) ??
    numberAttr(image.dataset.displayHeight) ??
    numberAttr(image.getAttribute("height"));
  if (width !== null) {
    figure.dataset.displayWidth = String(width);
    image.dataset.displayWidth = String(width);
    image.setAttribute("width", String(Math.round(width)));
    image.style.width = `${width}px`;
  }
  if (height !== null) {
    figure.dataset.displayHeight = String(height);
    image.dataset.displayHeight = String(height);
    image.setAttribute("height", String(Math.round(height)));
    image.style.height = `${height}px`;
  }
}

function looksLikeFullDocument(value: string): boolean {
  return /<\/?(article|h1|h2)\b/i.test(value) && looksLikeHtml(value);
}

function looksLikeHtml(value: string): boolean {
  return /<\/?(div|p|br|table|tr|td|th|figure|img|section|article|h[1-6]|ul|ol|li)\b/i.test(
    value,
  );
}

function decodeEscapedLineBreaks(value: string): string {
  return value
    .replace(/&lt;br\s*\/?&gt;/gi, "<br>")
    .replace(/&lt;br&gt;/gi, "<br>");
}

function cleanText(value: string): string {
  return value.replace(/[\u00a0\u3000]/g, " ").replace(/[ \t\v\f]+/g, " ").trim();
}

function escapeHtml(value: string): string {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function cssValue(style: string | null, name: string): string | null {
  if (style === null) return null;
  for (const part of style.split(";")) {
    const [key, ...rest] = part.split(":");
    if (key?.trim().toLowerCase() === name) return rest.join(":").trim();
  }
  return null;
}

function stringAttr(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function numberAttr(value: unknown): number | null {
  if (value === null || value === undefined || value === "") return null;
  const match = String(value).trim().match(/^([0-9]+(?:\.[0-9]+)?)/);
  if (match === null) return null;
  const numeric = Number(match[1]);
  return Number.isFinite(numeric) && numeric > 0 ? numeric : null;
}
