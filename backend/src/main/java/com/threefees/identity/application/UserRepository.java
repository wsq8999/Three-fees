package com.threefees.identity.application;

import com.threefees.identity.domain.UserAccount;
import java.util.List;
import java.util.Optional;

public interface UserRepository {

  Optional<UserAccount> findByUsername(String username);

  Optional<UserAccount> findById(long id);

  long count();

  long count(String keyword, String cityCode, Boolean enabled);

  List<UserAccount> findPage(int offset, int limit);

  List<UserAccount> findPage(
      String keyword, String cityCode, Boolean enabled, String sort, int offset, int limit);

  void createInitialAccount(BootstrapAccount account, String passwordHash);

  long createCityUser(String username, String displayName, String passwordHash, String cityCode);

  boolean update(long id, String displayName, String cityCode, boolean enabled, long version);

  boolean updatePassword(long id, String passwordHash, boolean mustChangePassword);
}
