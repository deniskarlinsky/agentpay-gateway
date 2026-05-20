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
 *
 * <p>The bean is registered under the exact name {@code transactionManager}: Spring Data JPA's
 * auto-configuration wires every repository's transactional advice with the default qualifier
 * {@code "transactionManager"}, so {@code CrudRepository.findById} et al. look the bean up by
 * that name — {@link Primary} alone is not enough.
 */
@Configuration
public class JpaTransactionConfig {

  @Bean
  @Primary
  public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
    return new JpaTransactionManager(emf);
  }
}
