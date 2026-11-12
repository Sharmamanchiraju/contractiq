package com.contractiq.common.security;

import com.contractiq.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

/**
 * Creates and checks the short-lived JWTs that prove who is calling. Fails at startup when the
 * secret is missing or shorter than 32 bytes, except in the dev profile where a missing secret is
 * replaced by a random one (tokens then stop working after a restart).
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final int MIN_SECRET_BYTES = 32;
    private static final String CLAIM_ORGANIZATION = "org";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final Duration expiry;
    private final Clock clock;

    public JwtService(JwtProperties properties, Environment environment, Clock clock) {
        this.key = Keys.hmacShaKeyFor(resolveSecret(properties.secret(), environment));
        this.expiry = Duration.ofMinutes(properties.expiryMinutes());
        this.clock = clock;
    }

    private static byte[] resolveSecret(String configured, Environment environment) {
        boolean missing = configured == null || configured.isBlank();
        if (missing && environment.acceptsProfiles(Profiles.of("dev"))) {
            log.warn("JWT_SECRET is not set; using a random secret for this dev run only");
            byte[] random = new byte[MIN_SECRET_BYTES];
            new SecureRandom().nextBytes(random);
            return random;
        }
        if (missing) {
            throw new IllegalStateException("JWT_SECRET is required (at least 32 bytes) outside the dev profile");
        }
        byte[] bytes = configured.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        }
        return bytes;
    }

    /** How long a newly issued token stays valid, in seconds. */
    public long expirySeconds() {
        return expiry.toSeconds();
    }

    /**
     * Issues a signed token carrying the user id (subject), organization id and role.
     */
    public String issue(Long userId, Long organizationId, Role role) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_ORGANIZATION, organizationId)
                .claim(CLAIM_ROLE, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiry)))
                .signWith(key)
                .compact();
    }

    /**
     * Returns the caller described by the token, or empty if the token is malformed, tampered
     * with, expired or missing a required claim. Never throws and never logs the token.
     */
    public Optional<AuthenticatedUser> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            Long organizationId = claims.get(CLAIM_ORGANIZATION, Long.class);
            Role role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));
            if (organizationId == null) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedUser(userId, organizationId, role));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
