package com.threefees.identity.infrastructure.security;

import com.threefees.identity.application.CurrentUser;
import com.threefees.identity.domain.Role;
import com.threefees.identity.domain.UserAccount;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record AppUserPrincipal(
    long id,
    String username,
    String displayName,
    String passwordHash,
    String cityCode,
    String cityName,
    boolean enabled,
    boolean mustChangePassword,
    Set<Role> roles)
    implements UserDetails, CurrentUser, Serializable {

  @Serial private static final long serialVersionUID = 1L;

  public AppUserPrincipal {
    roles = Set.copyOf(roles);
  }

  static AppUserPrincipal from(UserAccount account) {
    return new AppUserPrincipal(
        account.id(),
        account.username(),
        account.displayName(),
        account.passwordHash(),
        account.cityCode(),
        account.cityName(),
        account.enabled(),
        account.mustChangePassword(),
        account.roles());
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return roles.stream()
        .map(Role::name)
        .map(role -> "ROLE_" + role)
        .map(SimpleGrantedAuthority::new)
        .toList();
  }

  @Override
  public String getPassword() {
    return passwordHash;
  }

  @Override
  public String getUsername() {
    return username;
  }

  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @Override
  public boolean isAccountNonLocked() {
    return true;
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }
}
