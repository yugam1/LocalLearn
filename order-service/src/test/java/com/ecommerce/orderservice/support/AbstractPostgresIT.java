package com.ecommerce.orderservice.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton-container base for the PostgreSQL-backed integration tests.
 *
 * <p>Deliberately <em>not</em> using {@code @Testcontainers} +
 * {@code @Container}: that manages the container per test class, so N test
 * classes means N container starts (~5s each). Starting it once in a static
 * initializer and never stopping it lets every subclass share one database —
 * Ryuk (the Testcontainers reaper sidecar) tears it down when the JVM exits.
 *
 * <p>Requires a running Docker daemon. These tests are named {@code *IT} so
 * Failsafe runs them at {@code mvn verify}, keeping {@code mvn test} usable
 * without Docker.
 */
public abstract class AbstractPostgresIT {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                    .withDatabaseName("orderdb_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    /**
     * Rewrites the datasource properties before the Spring context is built —
     * the container's port is random, so it cannot be known at compile time.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }
}
