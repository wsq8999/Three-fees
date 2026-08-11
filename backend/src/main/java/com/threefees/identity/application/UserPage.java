package com.threefees.identity.application;

import com.threefees.identity.domain.UserAccount;
import java.util.List;

public record UserPage(
    List<UserAccount> items, int page, int size, long totalElements, int totalPages) {

  public UserPage {
    items = List.copyOf(items);
  }
}
