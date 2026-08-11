package com.threefees.importing.api;

import java.util.List;

public record ImportBatchPageResponse(
    List<ImportBatchResponse> items, int page, int size, long totalElements, int totalPages) {}
