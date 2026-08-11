package com.threefees.identity.infrastructure.bootstrap;

import com.threefees.identity.application.InitialAccountService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression(
    "'${three-fees.process-role:api}' == 'api' or '${three-fees.process-role:api}' == 'all'")
public class InitialAccountBootstrapper implements ApplicationRunner {

  private final InitialAccountService initialAccountService;
  private final boolean enabled;
  private final String initialPassword;

  public InitialAccountBootstrapper(
      InitialAccountService initialAccountService,
      @Value("${app.bootstrap.enabled:true}") boolean enabled,
      @Value("${app.bootstrap.initial-password:}") String initialPassword) {
    this.initialAccountService = initialAccountService;
    this.enabled = enabled;
    this.initialPassword = initialPassword;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (enabled) {
      initialAccountService.initializeIfEmpty(initialPassword);
    }
  }
}
