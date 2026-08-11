package com.threefees.importing.application;

import java.util.List;

public record TabularData(List<String> headers, List<List<String>> rows) {

  public TabularData {
    headers = List.copyOf(headers);
    rows = rows.stream().map(List::copyOf).toList();
  }
}
