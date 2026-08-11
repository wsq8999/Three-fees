package com.threefees.identity.application;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InitialAccountService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public InitialAccountService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional
  public int initializeIfEmpty(String initialPassword) {
    if (userRepository.count() > 0) {
      return 0;
    }
    if (initialPassword == null || initialPassword.isBlank()) {
      throw new IllegalStateException(
          "INITIAL_ACCOUNT_PASSWORD must be provided when the account table is empty");
    }

    for (BootstrapAccount account : BootstrapAccountCatalog.accounts()) {
      userRepository.createInitialAccount(account, passwordEncoder.encode(initialPassword));
    }
    return BootstrapAccountCatalog.accounts().size();
  }
}
