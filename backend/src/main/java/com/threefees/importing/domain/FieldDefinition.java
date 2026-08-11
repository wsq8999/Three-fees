package com.threefees.importing.domain;

public record FieldDefinition(
    int order,
    String technicalName,
    String sourceName,
    String businessGroup,
    String suggestedType,
    String purpose) {}
