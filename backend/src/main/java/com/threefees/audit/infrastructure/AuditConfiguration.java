package com.threefees.audit.infrastructure;

import com.threefees.audit.domain.AuditCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuditConfiguration {

  @Bean
  AuditCalculator auditCalculator() {
    return new AuditCalculator();
  }
}
