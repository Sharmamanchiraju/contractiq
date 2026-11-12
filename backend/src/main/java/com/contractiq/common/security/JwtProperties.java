package com.contractiq.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * JWT settings bound from {@code app.jwt.*}. The secret comes from the JWT_SECRET environment variable.
 *
 * @param secret        signing secret; at least 32 bytes outside the dev profile
 * @param expiryMinutes how long a token is valid
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(@DefaultValue("") String secret, @DefaultValue("30") long expiryMinutes) {
}
