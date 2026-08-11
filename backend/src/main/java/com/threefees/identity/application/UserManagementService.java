package com.threefees.identity.application;

import com.threefees.identity.domain.Role;
import com.threefees.identity.domain.UserAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserManagementService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final String configuredInitialPassword;

  public UserManagementService(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      @Value("${app.bootstrap.initial-password:}") String configuredInitialPassword) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.configuredInitialPassword = configuredInitialPassword;
  }

  @Transactional
  public UserAccount create(
      String username, String displayName, String cityCode, String requestedInitialPassword) {
    if (userRepository.findByUsername(username).isPresent()) {
      throw new ResourceConflictException("USERNAME_ALREADY_EXISTS", "用户名已经存在");
    }
    String initialPassword = passwordOrConfigured(requestedInitialPassword);
    long id =
        userRepository.createCityUser(
            username, displayName, passwordEncoder.encode(initialPassword), cityCode);
    return userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("用户"));
  }

  @Transactional
  public UserAccount update(
      long id, String displayName, String cityCode, boolean enabled, long expectedVersion) {
    UserAccount existing = find(id);
    if (existing.roles().contains(Role.SUPER_ADMIN) && !enabled) {
      throw new BusinessRuleException("ADMIN_CANNOT_BE_DISABLED", "超级管理员不能停用自身账号");
    }
    if (!userRepository.update(id, displayName, cityCode, enabled, expectedVersion)) {
      throw new ResourceConflictException("STALE_USER_VERSION", "用户已被其他请求修改，请刷新后重试");
    }
    return find(id);
  }

  @Transactional
  public void resetPassword(long id, String requestedPassword) {
    find(id);
    if (!userRepository.updatePassword(
        id, passwordEncoder.encode(passwordOrConfigured(requestedPassword)), true)) {
      throw new ResourceNotFoundException("用户");
    }
  }

  @Transactional
  public void changeOwnPassword(
      CurrentUser currentUser, String currentPassword, String newPassword) {
    UserAccount account =
        userRepository
            .findByUsername(currentUser.username())
            .orElseThrow(() -> new ResourceNotFoundException("用户"));
    if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
      throw new BusinessRuleException("CURRENT_PASSWORD_INCORRECT", "当前密码不正确");
    }
    if (passwordEncoder.matches(newPassword, account.passwordHash())) {
      throw new BusinessRuleException("PASSWORD_NOT_CHANGED", "新密码不能与当前密码相同");
    }
    userRepository.updatePassword(account.id(), passwordEncoder.encode(newPassword), false);
  }

  @Transactional(readOnly = true)
  public UserAccount find(long id) {
    return userRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("用户"));
  }

  private String passwordOrConfigured(String requestedPassword) {
    String password =
        requestedPassword == null || requestedPassword.isBlank()
            ? configuredInitialPassword
            : requestedPassword;
    if (password == null || password.length() < 6 || password.length() > 72) {
      throw new BusinessRuleException("INITIAL_PASSWORD_REQUIRED", "必须通过请求或运行环境提供 6 至 72 位初始密码");
    }
    return password;
  }
}
