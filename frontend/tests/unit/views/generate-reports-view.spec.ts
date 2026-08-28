import { flushPromises, mount } from "@vue/test-utils";
import { describe, expect, it, vi, beforeEach } from "vitest";

import type { ReportGenerationCandidate, ReportDraft } from "@/types/business";

const mocks = vi.hoisted(() => ({
  candidates: vi.fn(),
  createOrResume: vi.fn(),
  push: vi.fn(),
}));

const routeState = vi.hoisted(() => ({
  query: {},
}));

vi.mock("@/api/business-api", () => ({
  businessApi: {
    drafts: {
      createOrResume: mocks.createOrResume,
    },
    reportGeneration: {
      candidates: mocks.candidates,
      correctionInitialContent: vi.fn(),
    },
  },
}));

vi.mock("@/stores/session", () => ({
  useSessionStore: () => ({
    currentUser: {
      city: { code: "320100", name: "南京市" },
    },
  }),
}));

vi.mock("vue-router", () => ({
  useRoute: () => routeState,
  useRouter: () => ({
    push: mocks.push,
  }),
  onBeforeRouteLeave: vi.fn(),
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

import GenerateReportsView from "@/views/reports/GenerateReportsView.vue";

function candidate(
  code: string,
  name: string,
  draftAnalysisStatus?: string | null,
  draftAnalysisTaskStatus?: string | null,
): ReportGenerationCandidate {
  return {
    billingPointPeriodId: `snapshot-${code}`,
    billingPointCode: code,
    billingPointName: name,
    cityCode: "320100",
    cityName: "南京市",
    district: "鼓楼区",
    period: "2026-06",
    overLimitType: "同比超标",
    maxExceedRatio: "18.5",
    overLimitRatios: [{ type: "YOY", label: "同比", ratio: "18.5" }],
    draftId: draftAnalysisStatus || draftAnalysisTaskStatus ? `draft-${code}` : null,
    draftAnalysisStatus,
    draftAnalysisTaskStatus,
  };
}

function draftFixture(id: string): ReportDraft {
  return {
    id,
    billingPointId: "snapshot-BP-A",
    billingPointCode: "BP-A",
    billingPointName: "分析中报账点",
    city: { code: "320100", name: "南京市" },
    period: "2026-06",
    status: "AI_ANALYZING",
    analysisStatus: "AI_ANALYZING",
    analysisTaskId: "task-1",
    analysisErrorCode: null,
    analysisSubmittedAt: "2026-08-28T10:00:00",
    analysisCompletedAt: null,
    blocks: [],
    imageFileIds: [],
    messages: [],
    currentVersion: 1,
    updatedAt: "2026-08-28T10:00:00",
    formalReportId: null,
    entityVersion: 1,
  };
}

function mountView(candidates: ReportGenerationCandidate[]) {
  mocks.candidates.mockResolvedValue(candidates);
  mocks.createOrResume.mockResolvedValue(draftFixture("draft-BP-A"));
  return mount(GenerateReportsView, {
    global: {
      stubs: {
        ElButton: {
          props: ["loading", "disabled"],
          emits: ["click"],
          template:
            '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
        },
        ElDialog: true,
        ElEmpty: true,
        ElInput: true,
        ElOption: {
          props: ["label", "value"],
          template:
            '<div class="test-option" :data-value="value"><slot>{{ label }}</slot></div>',
        },
        ElSelect: {
          props: ["modelValue"],
          template: '<div class="test-select"><slot /></div>',
        },
        ElTag: {
          props: ["type"],
          template: '<span class="test-tag" :data-type="type"><slot /></span>',
        },
        OverLimitRatioTags: true,
        OverLimitTypeTags: true,
        PageState: true,
      },
    },
  });
}

describe("GenerateReportsView candidate status labels", () => {
  beforeEach(() => {
    routeState.query = {};
    Object.values(mocks).forEach((mock) => mock.mockReset());
  });

  it("shows the same AI task status labels as the task progress page", async () => {
    const wrapper = mountView([
      candidate("BP-A", "排队中报账点", "AI_ANALYZING", "QUEUED"),
      candidate("BP-B", "分析中报账点", "AI_ANALYZING", "RUNNING"),
      candidate("BP-C", "等待重试报账点", "AI_ANALYZING", "RETRY_WAIT"),
      candidate("BP-D", "已分析报账点", "AI_COMPLETED_PENDING_CONFIRMATION", "SUCCEEDED"),
      candidate("BP-E", "分析失败报账点", "AI_FAILED", "FAILED"),
      candidate("BP-F", "未分析报账点", "PENDING_ANALYSIS"),
      candidate("BP-G", "只有草稿状态报账点", "AI_COMPLETED_PENDING_CONFIRMATION"),
    ]);
    await flushPromises();

    expect(wrapper.text()).toContain("BP-A | 排队中报账点 | 南京市 | 2026-06");
    expect(wrapper.text()).toContain("排队中");
    expect(wrapper.text()).toContain("AI分析中");
    expect(wrapper.text()).toContain("等待重试");
    expect(wrapper.text()).toContain("AI完成待确认");
    expect(wrapper.text()).toContain("AI分析失败");
    expect(wrapper.text()).not.toContain("待分析");
    expect(wrapper.text()).not.toContain("只有草稿状态报账点AI完成待确认");
    expect(wrapper.findAll(".test-tag").map((tag) => tag.attributes("data-type"))).toEqual([
      "info",
      "warning",
      "warning",
      "success",
      "danger",
    ]);
  });

  it("keeps entering the existing draft when a labeled candidate is opened", async () => {
    const wrapper = mountView([candidate("BP-A", "分析中报账点", "AI_ANALYZING")]);
    await flushPromises();

    await wrapper.find("button").trigger("click");
    await flushPromises();

    expect(mocks.createOrResume).toHaveBeenCalledWith("snapshot-BP-A");
    expect(mocks.push).toHaveBeenCalledWith({
      name: "report-draft",
      params: { draftId: "draft-BP-A" },
      query: { from: "/reports/generate" },
    });
  });
});
