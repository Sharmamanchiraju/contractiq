package com.contractiq.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Passwords for the dev-profile demo users, bound from {@code app.seed.*} (which reads the
 * SEED_*_PASSWORD environment variables). An empty password means that user is not created.
 */
@ConfigurationProperties(prefix = "app.seed")
public record SeedProperties(
        @DefaultValue("") String uploaderPassword,
        @DefaultValue("") String reviewerPassword,
        @DefaultValue("") String adminPassword) {
}
