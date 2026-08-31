package com.ecommerce.orderservice.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Boot's auto-configured JpaTransactionManager does not allow nested
 * transactions by default. Propagation.NESTED (used by
 * OrderNestedTransactionHelper for the batch-order-create savepoint demo,
 * docs/phase1_task5.md) requires it explicitly, and requires a JDBC driver
 * that supports savepoints (PostgreSQL does).
 */
@Configuration
public class TransactionConfig {

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        JpaTransactionManager transactionManager = new JpaTransactionManager(entityManagerFactory);
        transactionManager.setNestedTransactionAllowed(true);
        return transactionManager;
    }
}
