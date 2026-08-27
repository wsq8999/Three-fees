import { flushPromises, mount } from "@vue/test-utils";
import type { ComponentPublicInstance } from "vue";
import type { Editor } from "@tiptap/vue-3";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ReportDraft } from "@/types/business";

const mocks = vi.hoisted(() => ({
  discardUnusedCorrection: vi.fn(),
  generate: vi.fn(),
  get: vi.fn(),
  save: vi.fn(),
  sendMessage: vi.fn(),
  uploadImage: vi.fn(),
}));

const routeState = vi.hoisted(() => ({
  params: { draftId: "draft-1" },
  query: {},
}));

vi.mock("@/api/business-api", () => ({
  businessApi: {
    drafts: mocks,
  },
}));

vi.mock("vue-router", () => ({
  useRoute: () => routeState,
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
  }),
}));

vi.mock("@/utils/message-box", () => ({
  standardConfirm: vi.fn(),
}));

vi.mock("element-plus", async (importOriginal) => {
  const original = await importOriginal<typeof import("element-plus")>();
  return {
    ...original,
    ElMessage: {
      error: vi.fn(),
      info: vi.fn(),
      success: vi.fn(),
      warning: vi.fn(),
    },
  };
});

import ReportDraftView from "@/views/reports/ReportDraftView.vue";

type DraftViewTesting = {
  __testing: {
    applyRemoteDraft: (
      loaded: ReportDraft,
      refreshEditor: boolean,
      expectedDraftId?: string,
    ) => Promise<void>;
    flushDraftSave: (showSuccess?: boolean) => Promise<boolean>;
    sectionEditors: {
      analysis: { value: Editor | null };
      rectification: { value: Editor | null };
      situation: { value: Editor | null };
    };
  };
};

function cloneDraft(draft: ReportDraft): ReportDraft {
  return JSON.parse(JSON.stringify(draft)) as ReportDraft;
}

function draftFixture(overrides: Partial<ReportDraft> = {}): ReportDraft {
  const draft: ReportDraft = {
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
      { id: "title", type: "HEADING", title: "报告标题", content: "测试报告" },
      {
        id: "situation",
        type: "SITUATION",
        title: "情况说明",
        content: "<p>情况</p>",
      },
      {
        id: "analysis",
        type: "ANALYSIS",
        title: "审计分析",
        content:
          '<p>旧分析</p><img class="inline-report-image" data-file-id="img-1" />',
      },
      {
        id: "rectification",
        type: "RECTIFICATION",
        title: "整改建议",
        content: "<p>整改</p>",
      },
    ],
    imageFileIds: ["img-1"],
    messages: [],
    currentVersion: 1,
    updatedAt: "2026-08-26T00:00:00",
    formalReportId: null,
    entityVersion: 10,
    ...overrides,
  };
  return draft;
}

function mountDraftView(initialDraft = draftFixture()) {
  mocks.get.mockResolvedValue(cloneDraft(initialDraft));
  const wrapper = mount(ReportDraftView, {
    global: {
      stubs: {
        ElAlert: true,
        ElButton: true,
        ElEmpty: true,
        ElInput: true,
        ElOption: true,
        ElSelect: true,
        OverLimitRatioTags: true,
        OverLimitTypeTags: true,
        PageState: true,
      },
    },
  });
  return wrapper;
}

function testing(
  wrapper: ReturnType<typeof mountDraftView>,
): DraftViewTesting["__testing"] {
  return (wrapper.vm as ComponentPublicInstance & DraftViewTesting).__testing;
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe("ReportDraftView autosave", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    routeState.params.draftId = "draft-1";
    routeState.query = {};
    Object.values(mocks).forEach((mock) => mock.mockReset());
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("autosaves deleted text and filters deleted inline images", async () => {
    const savedDrafts: ReportDraft[] = [];
    mocks.save.mockImplementation(async (_id: string, draft: ReportDraft) => {
      const saved = cloneDraft(draft);
      savedDrafts.push(saved);
      return { ...saved, entityVersion: saved.entityVersion + 1 };
    });
    const wrapper = mountDraftView();
    await flushPromises();

    testing(wrapper).sectionEditors.analysis.value?.commands.setContent(
      "<p>删除后的分析</p>",
    );
    await vi.advanceTimersByTimeAsync(800);
    await flushPromises();

    expect(savedDrafts).toHaveLength(1);
    expect(
      savedDrafts[0]?.blocks.find((block) => block.type === "ANALYSIS")
        ?.content,
    ).toBe("<p>删除后的分析</p>");
    expect(savedDrafts[0]?.imageFileIds).toEqual([]);
  });

  it("queues a second save when content changes during an in-flight save", async () => {
    const savedDrafts: ReportDraft[] = [];
    const firstSave = deferred<ReportDraft>();
    mocks.save.mockImplementationOnce(
      async (_id: string, draft: ReportDraft) => {
        const saved = cloneDraft(draft);
        savedDrafts.push(saved);
        return firstSave.promise;
      },
    );
    mocks.save.mockImplementation(async (_id: string, draft: ReportDraft) => {
      const saved = cloneDraft(draft);
      savedDrafts.push(saved);
      return { ...saved, entityVersion: saved.entityVersion + 1 };
    });
    const wrapper = mountDraftView();
    await flushPromises();
    const editor = testing(wrapper).sectionEditors.analysis.value;

    editor?.commands.setContent("<p>第一次删除</p>");
    await vi.advanceTimersByTimeAsync(800);
    await flushPromises();
    expect(savedDrafts).toHaveLength(1);

    editor?.commands.setContent("<p>第二次删除</p>");
    await vi.advanceTimersByTimeAsync(800);
    firstSave.resolve({ ...savedDrafts[0]!, entityVersion: 11 });
    await testing(wrapper).flushDraftSave(false);
    await flushPromises();

    expect(savedDrafts).toHaveLength(2);
    expect(
      savedDrafts[1]?.blocks.find((block) => block.type === "ANALYSIS")
        ?.content,
    ).toBe("<p>第二次删除</p>");
  });

  it("does not overwrite dirty local editor content with remote polling data", async () => {
    const wrapper = mountDraftView();
    await flushPromises();
    const exposed = testing(wrapper);
    const editor = exposed.sectionEditors.analysis.value;

    editor?.commands.setContent("<p>本地未保存删除</p>");
    await exposed.applyRemoteDraft(
      draftFixture({
        blocks: [
          {
            id: "title",
            type: "HEADING",
            title: "报告标题",
            content: "测试报告",
          },
          {
            id: "situation",
            type: "SITUATION",
            title: "情况说明",
            content: "<p>情况</p>",
          },
          {
            id: "analysis",
            type: "ANALYSIS",
            title: "审计分析",
            content: "<p>远端旧内容</p>",
          },
          {
            id: "rectification",
            type: "RECTIFICATION",
            title: "整改建议",
            content: "<p>整改</p>",
          },
        ],
        entityVersion: 11,
      }),
      true,
    );

    expect(editor?.getHTML()).toBe("<p>本地未保存删除</p>");
  });
});
