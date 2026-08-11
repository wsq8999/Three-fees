package com.threefees.importing.domain;

public record ImportError(int row, String column, String code, String message) {}
