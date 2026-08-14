package com.threefees.identity.infrastructure.persistence;

import com.threefees.identity.application.BootstrapAccount;
import com.threefees.identity.application.UserRepository;
import com.threefees.identity.domain.Role;
import com.threefees.identity.domain.UserAccount;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisUserRepository implements UserRepository {

  private final UserMapper userMapper;

  public MyBatisUserRepository(UserMapper userMapper) {
    this.userMapper = userMapper;
  }

  @Override
  public Optional<UserAccount> findByUsername(String username) {
    return Optional.ofNullable(userMapper.findByUsername(username)).map(this::toDomain);
  }

  @Override
  public Optional<UserAccount> findById(long id) {
    return Optional.ofNullable(userMapper.findById(id)).map(this::toDomain);
  }

  @Override
  public long count() {
    return userMapper.count();
  }

  @Override
  public long count(String keyword, String cityCode, Boolean enabled) {
    return userMapper.countFiltered(keyword, pattern(keyword), cityCode, enabled);
  }

  @Override
  public List<UserAccount> findPage(int offset, int limit) {
    return userMapper.findPage(offset, limit).stream().map(this::toDomain).toList();
  }

  @Override
  public List<UserAccount> findPage(
      String keyword, String cityCode, Boolean enabled, String sort, int offset, int limit) {
    return userMapper
        .findPageFiltered(keyword, pattern(keyword), cityCode, enabled, sort, offset, limit)
        .stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public void createInitialAccount(BootstrapAccount account, String passwordHash) {
    int inserted =
        account.cityCode() == null
            ? userMapper.insertAdministrator(
                account.username(), account.displayName(), passwordHash)
            : userMapper.insertCityUser(
                account.username(), account.displayName(), passwordHash, account.cityCode());
    if (inserted != 1) {
      throw new IllegalStateException("Bootstrap account references an unknown city");
    }
    for (Role role : account.roles()) {
      if (userMapper.insertRole(account.username(), role.name()) != 1) {
        throw new IllegalStateException("Bootstrap role could not be persisted");
      }
    }
  }

  @Override
  public long createCityUser(
      String username, String displayName, String passwordHash, String cityCode, boolean enabled) {
    var holder = new GeneratedId();
    int inserted =
        userMapper.insertManagedCityUser(
            username, displayName, passwordHash, cityCode, enabled, "SYSTEM_ADMIN", holder);
    if (inserted != 1) {
      throw new IllegalArgumentException("Unknown city");
    }
    if (userMapper.insertRole(username, Role.CITY_USER.name()) != 1) {
      throw new IllegalStateException("Managed role could not be persisted");
    }
    return holder.getId() > 0 ? holder.getId() : findByUsername(username).orElseThrow().id();
  }

  @Override
  public boolean update(
      long id, String displayName, String cityCode, boolean enabled, long version) {
    return userMapper.updateManagedUser(id, displayName, cityCode, enabled, version, "SYSTEM_ADMIN")
        == 1;
  }

  @Override
  public boolean updatePassword(long id, String passwordHash, boolean mustChangePassword) {
    return userMapper.updatePassword(id, passwordHash, mustChangePassword, "PASSWORD_OPERATION")
        == 1;
  }

  private UserAccount toDomain(UserRow row) {
    Set<Role> roles =
        userMapper.findRoles(row.id()).stream()
            .map(RoleRow::roleCode)
            .map(Role::valueOf)
            .collect(Collectors.toUnmodifiableSet());
    return new UserAccount(
        row.id(),
        row.username(),
        row.displayName(),
        row.passwordHash(),
        row.cityCode(),
        row.cityName(),
        row.enabled(),
        row.mustChangePassword(),
        roles,
        row.updatedAt(),
        row.version());
  }

  private String pattern(String keyword) {
    if (keyword == null || keyword.isBlank()) {
      return null;
    }
    return "%" + keyword.trim().replace("%", "\\%").replace("_", "\\_") + "%";
  }
}
