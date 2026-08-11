package com.threefees.identity.api;

import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.domain.Role;
import com.threefees.identity.domain.UserAccount;
import java.time.LocalDateTime;
import java.util.List;

public record UserResponse(
    String id,
    String username,
    String displayName,
    List<String> roles,
    UserCityResponse city,
    boolean enabled,
    boolean mustChangePassword,
    LocalDateTime updatedAt,
    long version) {

  static UserResponse from(CurrentUser user) {
    return new UserResponse(
        Long.toString(user.id()),
        user.username(),
        user.displayName(),
        roleNames(user.roles().stream().toList()),
        city(user.cityCode(), user.cityName()),
        true,
        user.mustChangePassword(),
        null,
        0);
  }

  static UserResponse from(UserAccount user) {
    return new UserResponse(
        Long.toString(user.id()),
        user.username(),
        user.displayName(),
        roleNames(user.roles().stream().toList()),
        city(user.cityCode(), user.cityName()),
        user.enabled(),
        user.mustChangePassword(),
        user.updatedAt(),
        user.version());
  }

  private static List<String> roleNames(List<Role> roles) {
    return roles.stream().map(Role::name).sorted().toList();
  }

  private static UserCityResponse city(String code, String name) {
    return code == null ? null : new UserCityResponse(code, name);
  }
}
