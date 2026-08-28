export type DatasetType =
  "BILLING_POINT" | "PAYMENT" | "METER_READING" | "BENCHMARK";

export type ImportStatus =
  "QUEUED" | "PROCESSING" | "ACTIVE" | "FAILED" | "SUPERSEDED";

export interface ImportErrorItem {
  row: number;
  column: string;
  code?: string;
  message: string;
}

export interface ImportBatch {
  id: string;
  datasetType: DatasetType;
  period: string;
  periodStart?: string | null;
  periodEnd?: string | null;
  cityCode?: string;
  taskId?: string;
  fileName: string;
  status: ImportStatus;
  createdAt: string;
  completedAt: string | null;
  rowCount: number;
  errorCount: number;
  errors: ImportErrorItem[];
}

export type ImportSessionItemStatus =
  "NOT_STARTED" | "RUNNING" | "SUCCESS" | "FAILED" | "SUPERSEDED";

export interface ImportSessionItem {
  datasetType: DatasetType;
  status: ImportSessionItemStatus;
  batches: ImportBatch[];
}

export interface ImportSessionState {
  items: ImportSessionItem[];
  allCompleted: boolean;
  successfulTypes: DatasetType[];
  runningTypes: DatasetType[];
  failedTypes: DatasetType[];
  sessionStartedAt: string | null;
  sessionAnchorType: DatasetType | null;
}

export interface CreateImportInput {
  datasetType: DatasetType;
  period?: string;
  fileName: string;
}

export interface ExportJob {
  id: string;
  period: string;
  cityCode: string;
  datasetTypes: DatasetType[];
  billingPointIds: string[];
  taskId: string;
  status: "QUEUED" | "PROCESSING" | "SUCCEEDED" | "FAILED";
  errorCode: string | null;
  fileId: string | null;
  downloadUrl: string | null;
  createdAt: string;
  updatedAt: string;
}

export type AuditStatus =
  "NORMAL" | "OVER_LIMIT" | "PENDING_REVIEW" | "NOT_APPLICABLE";

export type ReportStatus = "NONE" | "DRAFT" | "FINAL" | "CORRECTED";

export interface OverLimitRatio {
  type: "YOY" | "MOM" | "RATED" | string;
  label: string;
  ratio: string | number | null;
}

export interface BusinessCity {
  code: string;
  name: string;
}

export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface BillingPointQuery {
  keyword?: string;
  code?: string;
  name?: string;
  cityCode: string;
  district?: string;
  period: string;
  paymentEligible?: boolean;
  billingPointStatus?: string;
  auditStatus: AuditStatus | "";
  reportStatus?: ReportStatus | "";
  focusPeriod?: string;
  focusCityCode?: string;
  page: number;
  size: number;
}

export interface FilterOptionCity {
  code: string;
  name: string;
}

export interface BillingPointFilterOptions {
  periods: string[];
  cities: FilterOptionCity[];
  districts: string[];
}

export interface BillingPointSummary {
  id: string;
  code: string;
  name: string;
  city: BusinessCity;
  period: string;
  address: string;
  district?: string | null;
  siteName?: string | null;
  electricityCategory: string;
  billingPointStatus?: string | null;
  paymentEligibility: "ELIGIBLE" | "INELIGIBLE" | "PENDING";
  actualEnergy: string | number | null;
  actualAmount?: string | number | null;
  benchmarkEnergy: string | null;
  deviationRate: string | null;
  overLimitRatios: OverLimitRatio[];
  overLimitType?: string | null;
  overLimitDisplayType?: string | null;
  auditStatus: AuditStatus;
  reportStatus: ReportStatus;
  draftId?: string | null;
  draftAnalysisStatus?: "AI_ANALYZING" | string | null;
  reportId?: string | null;
  reportNumber?: string | null;
  periodStart?: string | null;
  periodEnd?: string | null;
  paymentCodes?: string[];
}

export interface BusinessField {
  key: string;
  label: string;
  value: string;
  unit?: string;
}

export interface FieldGroup {
  title: string;
  fields: BusinessField[];
}

export interface PaymentRecord {
  id: string;
  paymentCode?: string | null;
  billNumber: string;
  payee: string;
  electricityFee: string;
  billingEnergy: string;
  approvalStatus: "APPROVED" | "REJECTED" | "PENDING";
  eligible: boolean;
  fieldGroups: FieldGroup[];
  fields?: BusinessField[];
}

export interface MeterRecord {
  id: string;
  paymentCode?: string | null;
  meterCode?: string | null;
  meterNumber: string;
  previousReading: string;
  currentReading: string;
  multiplier: string;
  allocatedEnergy: string;
  valid: boolean;
  fieldGroups: FieldGroup[];
  fields?: BusinessField[];
}

export interface BenchmarkRecord {
  id: string;
  benchmarkType: string;
  value: string;
  effectiveFrom: string;
  ruleVersion: string;
  fieldGroups: FieldGroup[];
}

export interface AuditComparison {
  key: "YEAR_ON_YEAR" | "MONTH_ON_MONTH" | "RATED_BENCHMARK";
  label: string;
  status: AuditStatus;
  referencePeriod: string | null;
  baseline: string | null;
  threshold: string | null;
  actual: string | null;
  difference: string | null;
  ratio: string | null;
  reason: string;
  formula: string;
}

export interface BillingPointDetail {
  summary: BillingPointSummary;
  overviewGroups: FieldGroup[];
  payments: PaymentRecord[];
  meters: MeterRecord[];
  benchmarks: BenchmarkRecord[];
  audit: {
    finalStatus: AuditStatus;
    finalReason: string;
    ruleVersion: string;
    calculatedAt: string;
    eligibilityReason: string;
    comparisons: AuditComparison[];
  };
  draftId: string | null;
}

export interface DashboardData {
  currentDataPeriod: string | null;
  availablePeriods: string[];
  imports: Array<{
    datasetType: DatasetType;
    activeBatch: ImportBatch | null;
  }>;
  cityCount: number;
  siteCount: number;
  lastUpdatedAt: string | null;
  billingPointCount: number;
  normalBillingPointCount: number;
  overLimitBillingPointCount: number;
  pendingReviewCount: number;
  draftReportCount: number;
  pendingReportCount: number;
  finalReportCount: number;
  districtOverLimitCounts: Array<{ name: string; count: number }>;
  districtMaxOverLimitRatios: Array<{ name: string; ratio: number }>;
  overLimitTypeCounts: Array<{ name: string; count: number }>;
  pendingTasks: Array<{
    id: string;
    billingPointPeriodId?: string;
    title: string;
    description: string;
    target: string;
    severity: "INFO" | "WARNING" | "DANGER";
    billingPointCode: string;
    billingPointName: string;
    cityName: string;
    county: string;
    period: string;
    actualAmount: string;
    overLimitType: string;
    maximumRatio: string;
    overLimitRatios: OverLimitRatio[];
    draftAnalysisStatus?: "AI_ANALYZING" | string | null;
  }>;
}

export type DraftIntent = "AUTO" | "ASK" | "EDIT" | "CORRECTION" | "IMAGE_ANALYSIS";

export interface DraftBlock {
  id: string;
  type: "HEADING" | "SITUATION" | "ANALYSIS" | "RECTIFICATION" | "IMAGE";
  title: string;
  content: string;
  imageName?: string;
}

export interface DraftMessage {
  id: string;
  role: "USER" | "ASSISTANT";
  intent: DraftIntent;
  content: string;
  imageNames: string[];
  createdAt: string;
}

export interface ReportDraft {
  id: string;
  billingPointId: string;
  billingPointCode?: string;
  billingPointName: string;
  city?: BusinessCity | null;
  period: string;
  overLimitType?: string | null;
  maxExceedRatio?: string | null;
  overLimitRatios?: OverLimitRatio[];
  status:
    | "EDITING"
    | "AI_ANALYZING"
    | "AI_COMPLETED"
    | "AI_FAILED"
    | "GENERATING"
    | "FINALIZED";
  analysisStatus:
    | "PENDING_ANALYSIS"
    | "AI_ANALYZING"
    | "AI_COMPLETED_PENDING_CONFIRMATION"
    | "AI_FAILED"
    | "FORMALIZED";
  analysisTaskId: string | null;
  analysisErrorCode: string | null;
  analysisSubmittedAt: string | null;
  analysisCompletedAt: string | null;
  blocks: DraftBlock[];
  imageFileIds: string[];
  messages: DraftMessage[];
  currentVersion: number;
  updatedAt: string;
  formalReportId: string | null;
  entityVersion: number;
}

export interface SendDraftMessageInput {
  intent: DraftIntent;
  content: string;
  imageNames: string[];
  imageFileIds?: string[];
}

export type AiTaskStatus =
  | "QUEUED"
  | "RUNNING"
  | "RETRY_WAIT"
  | "SUCCEEDED"
  | "FAILED";

export type AiTaskDraftStatus =
  | "EDITING"
  | "AI_ANALYZING"
  | "AI_COMPLETED"
  | "AI_FAILED"
  | "FINALIZED";

export interface AiTaskListQuery {
  status?: AiTaskStatus | "";
  billingPointName?: string;
  cityName?: string;
  period?: string;
  page: number;
  size: number;
}

export interface AiTaskSummary {
  queued: number;
  running: number;
  retryWait: number;
  completedPendingConfirmation: number;
  failed: number;
}

export interface AiTaskListItem {
  id: string;
  type: "AI_IMAGE_ANALYSIS";
  status: AiTaskStatus;
  attempts: number;
  maxAttempts: number;
  errorCode: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
  relatedDraftId: string | null;
  relatedReportId: string | null;
  billingPointName: string | null;
  cityName: string | null;
  period: string | null;
  draftStatus: AiTaskDraftStatus;
  canRetry: boolean;
  retryBlockedReason?: string | null;
}

export interface AiTaskPage extends PageResult<AiTaskListItem> {
  summary: AiTaskSummary;
  filterOptions?: {
    cityNames: string[];
    periods: string[];
  };
}

export interface ReportSummary {
  id: string;
  reportNumber: string;
  billingPointId: string;
  billingPointCode: string;
  billingPointName: string;
  city: BusinessCity;
  district?: string | null;
  period: string;
  periodStart?: string | null;
  periodEnd?: string | null;
  status: "FINAL" | "CORRECTED" | "HISTORICAL_IMPORTED";
  source: "SYSTEM" | "HISTORICAL_IMPORT";
  generatedAt: string;
  correctedAt: string | null;
  correctionCount: number;
  actualEnergy?: string | number | null;
  actualAmount?: string | number | null;
  overLimitType?: string | null;
  maxRatio?: string | null;
  overLimitRatios?: OverLimitRatio[];
  wordFileName: string;
  pdfFileName: string;
  pdfAvailable?: boolean;
  summary: string;
  previewHtml?: string | null;
  archivedAudit: AuditComparison[];
  latestAudit: AuditComparison[];
  corrections: Array<{
    reason: string;
    operator: string;
    occurredAt: string;
    summary: string;
  }>;
}

export interface ReportGenerationCandidate {
  billingPointPeriodId: string;
  billingPointCode: string;
  billingPointName: string;
  cityCode: string;
  cityName: string;
  district?: string | null;
  period: string;
  overLimitType?: string | null;
  maxExceedRatio?: string | null;
  overLimitRatios?: OverLimitRatio[];
  draftId?: string | null;
  draftAnalysisStatus?: string | null;
  draftAnalysisTaskStatus?: AiTaskStatus | string | null;
}

export interface ReportGenerationInitialContent {
  candidate: ReportGenerationCandidate;
  contentHtml: string;
}

export interface ReportGenerationImageInput {
  fileName: string;
  mediaType: "image/png" | "image/jpeg";
  base64Data: string;
}

export interface ReportGenerationImageAnalysisInput {
  billingPointCode: string;
  period: string;
  contentHtml: string;
  instruction: string;
  images: ReportGenerationImageInput[];
}

export interface ReportGenerationImageAnalysisResult {
  answer: string;
  analysisText: string;
  updatedContentHtml: string;
}

export interface HistoricalReportBillingPoint {
  billingPointCode: string;
  billingPointName: string;
  cityCode: string;
  cityName: string;
}

export interface HistoricalReportPeriod {
  billingPointPeriodId: string;
  period: string;
  overLimitType?: string | null;
  maxRatio?: string | null;
  overLimitRatios?: OverLimitRatio[];
}

export interface HistoricalReportCandidate {
  billingPointPeriodId: string;
  billingPointCode: string;
  billingPointName: string;
  cityCode: string;
  cityName: string;
  period: string;
  overLimitType?: string | null;
  maxRatio?: string | null;
  overLimitRatios?: OverLimitRatio[];
}

export interface ReportQuery {
  keyword?: string;
  reportNumber?: string;
  billingPointCode?: string;
  billingPointName?: string;

  period: string;
  cityCode: string;
  district?: string;

  source: ReportSummary["source"] | "";

  page: number;
  size: number;
}

export interface ReportFilterOptions {
  periods: string[];
  cities: FilterOptionCity[];
  districts: string[];
}

export interface ManagedUser {
  id: string;
  username: string;
  displayName: string;
  roles: Array<"SUPER_ADMIN" | "CITY_USER">;
  city: BusinessCity | null;
  enabled: boolean;
  mustChangePassword: boolean;
  updatedAt: string;
  version: number;
}

export interface BenchmarkRule {
  key: "YEAR_ON_YEAR" | "MONTH_ON_MONTH" | "RATED_BENCHMARK";
  name: string;
  version: string;
  description: string;
  formula: string;
  chain: string[];
  example: Array<{ label: string; value: string }>;
  boundaries: string[];
  snapshotNote: string;
}

export interface ScenarioSnapshot {
  imports: ImportBatch[];
  billingPoints: BillingPointDetail[];
  drafts: ReportDraft[];
  reports: ReportSummary[];
  users: ManagedUser[];
}
