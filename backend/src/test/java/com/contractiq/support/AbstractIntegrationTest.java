package com.contractiq.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Base class for integration tests. Starts one MySQL 8 container for the whole test run and
 * points the application at it, so tests never touch the developer's real database.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    /** A fake signing secret for tests only (at least 32 bytes). */
    public static final String TEST_JWT_SECRET = "test-only-fake-jwt-secret-0123456789abcdef";

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("contractiq_test")
            .withUsername("test")
            .withPassword("test");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("app.jwt.secret", () -> TEST_JWT_SECRET);
    }
}
