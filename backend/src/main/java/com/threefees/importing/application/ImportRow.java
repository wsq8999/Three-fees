package com.threefees.importing.application;

public record ImportRow(
    int sourceRow,
    String cityCode,
    String billingPointCode,
    String billingPointName,
    String paymentCode,
    String meterCode,
    String businessKey,
    String valuesJson) {}
