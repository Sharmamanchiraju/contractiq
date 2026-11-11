package com.contractiq.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.contractiq.user.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit tests for {@link JwtService}: token round trip, expiry, tampering, and the startup
 * rules for the signing secret. No Spring context or database needed.
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-fake-secret-0123456789abcdef-xyz";
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-01-01T10:00:00Z"), ZoneOffset.UTC);

    private JwtService service(String secret, Clock clock, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new JwtService(new JwtProperties(secret, 30), env, clock);
    }

    @Test
    void tokenRoundTripsUserOrganizationAndRole() {
        JwtService jwt = service(SECRET, NOW);
        String token = jwt.issue(7L, 3L, Role.REVIEWER);

        AuthenticatedUser user = jwt.parse(token).orElseThrow();

        assertThat(user.userId()).isEqualTo(7L);
        assertThat(user.organizationId()).isEqualTo(3L);
        assertThat(user.role()).isEqualTo(Role.REVIEWER);
    }

    @Test
    void tokenIsValidJustBeforeThirtyMinutesAndRejectedAfter() {
        String token = service(SECRET, NOW).issue(1L, 1L, Role.ADMIN);

        Clock before = Clock.offset(NOW, Duration.ofMinutes(29));
        Clock after = Clock.offset(NOW, Duration.ofMinutes(31));

        assertThat(service(SECRET, before).parse(token)).isPresent();
        assertThat(service(SECRET, after).parse(token)).isEmpty();
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService jwt = service(SECRET, NOW);
        String token = jwt.issue(1L, 1L, Role.UPLOADER);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertThat(jwt.parse(tampered)).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String token = service("another-fake-secret-0123456789abcdef-abc", NOW).issue(1L, 1L, Role.ADMIN);

        assertThat(service(SECRET, NOW).parse(token)).isEmpty();
    }

    @Test
    void garbageAndEmptyInputAreRejected() {
        JwtService jwt = service(SECRET, NOW);

        assertThat(jwt.parse("not-a-jwt")).isEmpty();
        assertThat(jwt.parse("")).isEmpty();
        assertThat(jwt.parse(null)).isEmpty();
    }

    @Test
    void missingSecretFailsOutsideDevProfile() {
        assertThatThrownBy(() -> service("", NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service(null, NOW, "prod")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shortSecretFailsInEveryProfile() {
        String thirtyOneBytes = "x".repeat(31);

        assertThatThrownBy(() -> service(thirtyOneBytes, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service(thirtyOneBytes, NOW, "dev")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exactlyThirtyTwoBytesIsAccepted() {
        assertThat(service("x".repeat(32), NOW).issue(1L, 1L, Role.ADMIN)).isNotBlank();
    }

    @Test
    void missingSecretIsReplacedByRandomOneInDevProfile() {
        JwtService first = service("", NOW, "dev");
        JwtService second = service("", NOW, "dev");
        String token = first.issue(1L, 1L, Role.ADMIN);

        assertThat(first.parse(token)).isPresent();
        assertThat(second.parse(token)).isEmpty();
    }
}
