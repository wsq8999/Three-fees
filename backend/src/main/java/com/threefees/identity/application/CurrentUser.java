package com.threefees.identity.application;

import com.threefees.identity.domain.Role;
import java.util.Set;

public interface CurrentUser {

  long id();

  String username();

  String displayName();

  String cityCode();

  String cityName();

  boolean mustChangePassword();

  Set<Role> roles();
}
