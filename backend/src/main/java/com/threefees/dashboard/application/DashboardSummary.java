package com.threefees.dashboard.application;

import java.util.List;

public record DashboardSummary(
    String currentDataPeriod,
    List<String> availablePeriods,
    int cityCount,
    long billingPointCount,
    long overLimitBillingPointCount,
    long draftReportCount,
    long pendingReportCount,
    long siteCount,
    String lastUpdatedAt,
    long normalBillingPointCount,
    long pendingReviewCount,
    long finalReportCount,
    List<DatasetImportSummary> imports,
    List<NameCount> districtOverLimitCounts,
    List<NameRatio> districtMaxOverLimitRatios,
    List<NameCount> overLimitTypeCounts,
    List<PendingReportTask> pendingTasks) {

  public record DatasetImportSummary(String datasetType, Object activeBatch) {}

  public record NameCount(String name, long count) {}

  public record NameRatio(String name, java.math.BigDecimal ratio) {}

  public record PendingReportTask(
      String id,
      String billingPointPeriodId,
      String title,
      String description,
      String target,
      String severity,
      String billingPointCode,
      String billingPointName,
      String cityName,
      String county,
      String period,
      String actualAmount,
      String overLimitType,
      String maximumRatio,
      List<OverLimitRatio> overLimitRatios,
      String draftAnalysisStatus) {}

  public record OverLimitRatio(String type, String label, String ratio) {}
}
