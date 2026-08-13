package com.threefees.importing.domain;

import java.util.List;

public enum DatasetType {
  BILLING_POINT("catalog-billing-point.tsv", 73, List.of()),
  PAYMENT("catalog-payment.tsv", 198, List.of(BILLING_POINT)),
  METER_READING("catalog-meter-reading.tsv", 42, List.of(BILLING_POINT, PAYMENT)),
  BENCHMARK("catalog-benchmark.tsv", 39, List.of(BILLING_POINT));

  private final String resourceName;
  private final int fieldCount;
  private final List<DatasetType> prerequisites;

  DatasetType(String resourceName, int fieldCount, List<DatasetType> prerequisites) {
    this.resourceName = resourceName;
    this.fieldCount = fieldCount;
    this.prerequisites = List.copyOf(prerequisites);
  }

  public String resourceName() {
    return resourceName;
  }

  public int fieldCount() {
    return fieldCount;
  }

  public List<DatasetType> prerequisites() {
    return prerequisites;
  }
}
