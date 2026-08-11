package com.threefees.identity.api;

import com.threefees.identity.application.UserPage;
import java.util.List;

public record UserPageResponse(
    List<UserResponse> items, int page, int size, long totalElements, int totalPages) {

  static UserPageResponse from(UserPage page) {
    return new UserPageResponse(
        page.items().stream().map(UserResponse::from).toList(),
        page.page(),
        page.size(),
        page.totalElements(),
        page.totalPages());
  }
}
