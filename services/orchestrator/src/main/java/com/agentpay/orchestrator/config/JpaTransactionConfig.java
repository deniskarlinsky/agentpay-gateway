package com.agentpay.orchestrator.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Disambiguates the PlatformTransactionManager bean. spring-kafka auto-configures a {@code
 * kafkaTransactionManager}, which would otherwise make an unqualified {@code @Transactional}
 * ambiguous against the JPA-auto-configured one. Marking the JPA manager {@code @Primary} lets
 * {@code @Transactional} (no value) resolve to it without a string qualifier on every annotation.
 */
@Configuration
public class JpaTransactionConfig {

  @Bean
  @Primary
  public PlatformTransactionManager jpaTransactionManager(EntityManagerFactory emf) {
    return new JpaTransactionManager(emf);
  }
}
