package com.contractiq.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Slows down password guessing: after {@code maxFailures} failed logins within the window, a username
 * (and, separately, a network address) is refused until the oldest of those failures falls out of the window.
 *
 * <p><b>It cannot reveal whether a username exists.</b> A failure is counted for the name typed, whether
 * or not such a user exists, so real and made-up names are locked after exactly the same number of
 * attempts and refused in exactly the same way. A locked name is refused even with the right password.
 *
 * <p><b>Limits, honestly stated.</b>
 * <ul>
 *   <li>It lives in this application instance's memory: it resets when the application restarts and it
 *       is not shared between instances, so with N instances an attacker gets up to N times the allowance.</li>
 *   <li>Locking by username means someone can deliberately lock a real user out for the window by failing
 *       logins as them. That is the usual trade-off; the lock is short and it ends by itself.</li>
 *   <li>The address is the direct connection address. Behind a proxy or load balancer that is the proxy's
 *       address, so everyone shares one allowance until forwarded headers are trusted on purpose.</li>
 * </ul>
 */
@Component
public class LoginAttemptLimiter {

    /** The answer to "may this attempt go ahead?". */
    public record Decision(boolean blocked, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(false, 0);
    }

    /** A map that forgets its least recently used entry when it grows past a limit. */
    private static final class BoundedMap extends LinkedHashMap<String, ArrayDeque<Instant>> {
        private final int maxEntries;

        BoundedMap(int maxEntries) {
            super(16, 0.75f, true);
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ArrayDeque<Instant>> eldest) {
            return size() > maxEntries;
        }
    }

    private final Clock clock;
    private final int maxFailures;
    private final Duration window;
    private final BoundedMap failures;

    public LoginAttemptLimiter(Clock clock, LoginLimitProperties properties) {
        this.clock = clock;
        this.maxFailures = properties.maxFailures();
        this.window = properties.window();
        this.failures = new BoundedMap(properties.maxTrackedKeys());
    }

    /** Checks, before any password is verified, whether this username or address is locked out. */
    public synchronized Decision check(String username, String ipAddress) {
        Instant now = clock.instant();
        long retryAfter = 0;
        for (String key : keys(username, ipAddress)) {
            ArrayDeque<Instant> recent = recentFailures(key, now);
            if (recent != null && recent.size() >= maxFailures) {
                Instant freeAt = recent.peekFirst().plus(window);
                long seconds = Math.max(1, (Duration.between(now, freeAt).toMillis() + 999) / 1000);
                retryAfter = Math.max(retryAfter, seconds);
            }
        }
        return retryAfter > 0 ? new Decision(true, retryAfter) : Decision.ALLOWED;
    }

    /**
     * Counts a failed login against the username and the address. Returns true if this failure made
     * either of them reach the limit (so the caller can log the lock-out once, not on every refusal).
     */
    public synchronized boolean recordFailure(String username, String ipAddress) {
        Instant now = clock.instant();
        boolean reachedLimit = false;
        for (String key : keys(username, ipAddress)) {
            ArrayDeque<Instant> recent = recentFailures(key, now);
            if (recent == null) {
                recent = new ArrayDeque<>();
                failures.put(key, recent);
            }
            recent.addLast(now);
            if (recent.size() == maxFailures) {
                reachedLimit = true;
            }
        }
        return reachedLimit;
    }

    /** A correct login clears that username's failures (not the address's). */
    public synchronized void recordSuccess(String username) {
        failures.remove(userKey(username));
    }

    /** Forgets everything. For tests. */
    public synchronized void reset() {
        failures.clear();
    }

    /** How many usernames and addresses are being remembered. For tests. */
    synchronized int trackedKeys() {
        return failures.size();
    }

    /** The failures still inside the window for a key, or null if there are none (and the key is dropped). */
    private ArrayDeque<Instant> recentFailures(String key, Instant now) {
        ArrayDeque<Instant> recent = failures.get(key);
        if (recent == null) {
            return null;
        }
        Instant cutoff = now.minus(window);
        while (!recent.isEmpty() && !recent.peekFirst().isAfter(cutoff)) {
            recent.removeFirst();
        }
        if (recent.isEmpty()) {
            failures.remove(key);
            return null;
        }
        return recent;
    }

    private static String[] keys(String username, String ipAddress) {
        return new String[] {userKey(username), "ip:" + (ipAddress == null ? "unknown" : ipAddress)};
    }

    /** Usernames match case-insensitively in the database, so they must share one counter here too. */
    private static String userKey(String username) {
        return "u:" + (username == null ? "" : username.trim().toLowerCase(Locale.ROOT));
    }
}
