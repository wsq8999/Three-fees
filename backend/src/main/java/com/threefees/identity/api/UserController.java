package com.threefees.identity.api;

import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.application.UserManagementService;
import com.threefees.identity.application.UserQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

  private final UserQueryService userQueryService;
  private final UserManagementService userManagementService;

  public UserController(
      UserQueryService userQueryService, UserManagementService userManagementService) {
    this.userQueryService = userQueryService;
    this.userManagementService = userManagementService;
  }

  @GetMapping
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public UserPageResponse findPage(
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String cityCode,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(defaultValue = "USERNAME_ASC")
          @jakarta.validation.constraints.Pattern(
              regexp = "USERNAME_ASC|USERNAME_DESC|UPDATED_AT_ASC|UPDATED_AT_DESC")
          String sort,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return UserPageResponse.from(
        userQueryService.findPage(keyword, cityCode, enabled, sort, page, size));
  }

  @PostMapping
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
    var created =
        userManagementService.create(
            request.username(),
            request.displayName(),
            request.cityCode(),
            request.initialPassword());
    return ResponseEntity.created(URI.create("/api/v1/users/" + created.id()))
        .body(UserResponse.from(created));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public UserResponse find(@PathVariable long id) {
    return UserResponse.from(userManagementService.find(id));
  }

  @PatchMapping("/{id}")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public UserResponse update(@PathVariable long id, @Valid @RequestBody UpdateUserRequest request) {
    return UserResponse.from(
        userManagementService.update(
            id, request.displayName(), request.cityCode(), request.enabled(), request.version()));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<Void> disable(@PathVariable long id) {
    var user = userManagementService.find(id);
    userManagementService.update(id, user.displayName(), user.cityCode(), false, user.version());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/password-reset")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<Void> resetPassword(
      @PathVariable long id, @Valid @RequestBody ResetPasswordRequest request) {
    userManagementService.resetPassword(id, request.newPassword());
    return ResponseEntity.noContent().build();
  }

  @PatchMapping("/current/password")
  public ResponseEntity<Void> changePassword(
      @AuthenticationPrincipal CurrentUser currentUser,
      @Valid @RequestBody ChangePasswordRequest request) {
    userManagementService.changeOwnPassword(
        currentUser, request.currentPassword(), request.newPassword());
    return ResponseEntity.noContent().build();
  }
}
