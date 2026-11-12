package com.contractiq.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the failed-login limiter, bound from {@code app.security.login.*}.
 *
 * @param maxFailures    failed logins allowed per username, and per network address, within the window
 * @param window         how long a failure counts
 * @param maxTrackedKeys most usernames and addresses remembered at once; the oldest are forgotten
 *                       beyond this, so an attacker cannot make the limiter use unlimited memory
 */
@ConfigurationProperties(prefix = "app.security.login")
public record LoginLimitProperties(
        @DefaultValue("5") int maxFailures,
        @DefaultValue("15m") Duration window,
        @DefaultValue("10000") int maxTrackedKeys) {
}
