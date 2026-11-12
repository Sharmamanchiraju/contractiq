package com.contractiq.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.contractiq.auth.LoginAttemptLimiter.Decision;
import com.contractiq.support.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the failed-login limiter: the exact limit, the window, retry-after, independence of
 * the username and address counters, the memory bound, and that a lock does not extend itself.
 */
class LoginAttemptLimiterTest {

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock,
            new LoginLimitProperties(5, Duration.ofMinutes(15), 10_000));

    private void fail(String username, String ip, int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(username, ip);
        }
    }

    @Test
    void allowsFourFailuresAndBlocksAfterTheFifth() {
        fail("alice", "10.0.0.1", 4);
        assertThat(limiter.check("alice", "10.0.0.1").blocked()).isFalse();

        fail("alice", "10.0.0.1", 1);

        assertThat(limiter.check("alice", "10.0.0.1").blocked()).isTrue();
    }

    @Test
    void reportsAWaitThatEndsWhenTheOldestFailureLeavesTheWindow() {
        limiter.recordFailure("alice", "10.0.0.1");          // t = 0
        clock.advance(Duration.ofMinutes(3));
        fail("alice", "10.0.0.1", 4);                         // t = 3 min: now at the limit

        Decision decision = limiter.check("alice", "10.0.0.1");

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.retryAfterSeconds()).isEqualTo(12 * 60); // first failure expires at 15 min
        clock.advance(Duration.ofMinutes(12));
        assertThat(limiter.check("alice", "10.0.0.1").blocked()).isFalse();
    }

    @Test
    void theLockEndsOnceTheWindowHasPassed() {
        fail("alice", "10.0.0.1", 5);
        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(limiter.check("alice", "10.0.0.1").blocked()).isTrue();

        clock.advance(Duration.ofSeconds(1));

        assertThat(limiter.check("alice", "10.0.0.1").blocked()).isFalse();
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        fail("alice", "10.0.0.1", 5);
        clock.advance(Duration.ofMinutes(15).minusMillis(10));

        assertThat(limiter.check("alice", "10.0.0.1").retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void checkingWhileLockedDoesNotExtendTheLock() {
        fail("alice", "10.0.0.1", 5);
        for (int i = 0; i < 50; i++) {
            clock.advance(Duration.ofSeconds(10));
            limiter.check("alice", "10.0.0.1");
        }

        clock.advance(Duration.ofMinutes(15));

        assertThat(limiter.check("alice", "10.0.0.1").blocked()).isFalse();
    }

    @Test
    void usernamesShareOneCounterWhateverTheirCaseOrSpacing() {
        limiter.recordFailure("Alice", "10.0.0.1");
        limiter.recordFailure("alice ", "10.0.0.2");
        limiter.recordFailure(" ALICE", "10.0.0.3");
        limiter.recordFailure("alice", "10.0.0.4");
        limiter.recordFailure("aLiCe", "10.0.0.5");

        assertThat(limiter.check("alice", "10.0.0.99").blocked()).isTrue();
    }

    @Test
    void aMadeUpUsernameIsLockedAfterExactlyTheSameNumberOfFailuresAsARealOne() {
        // The limiter never asks whether a user exists, so it cannot behave differently for one.
        int realBlockedAt = firstBlockedAttempt("a-real-user", "10.1.0.1");
        int fakeBlockedAt = firstBlockedAttempt("no-such-user-12345", "10.1.0.2");

        assertThat(realBlockedAt).isEqualTo(6);
        assertThat(fakeBlockedAt).isEqualTo(6);
    }

    private int firstBlockedAttempt(String username, String ip) {
        for (int attempt = 1; attempt <= 20; attempt++) {
            if (limiter.check(username, ip).blocked()) {
                return attempt;
            }
            limiter.recordFailure(username, ip);
        }
        return -1;
    }

    @Test
    void anAddressIsBlockedByFailuresAcrossDifferentUsernames() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("user-" + i, "10.0.0.1");
        }

        assertThat(limiter.check("someone-new", "10.0.0.1").blocked()).isTrue();
        assertThat(limiter.check("someone-new", "10.0.0.2").blocked()).isFalse();
        assertThat(limiter.check("user-0", "10.0.0.2").blocked()).isFalse();
    }

    @Test
    void aUsernameIsBlockedByFailuresFromDifferentAddresses() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("alice", "10.0.0." + i);
        }

        assertThat(limiter.check("alice", "10.0.9.9").blocked()).isTrue();
        assertThat(limiter.check("bob", "10.0.9.9").blocked()).isFalse();
    }

    @Test
    void aSuccessClearsTheUsernamesFailuresButNotTheAddresss() {
        fail("alice", "10.0.0.1", 4);

        limiter.recordSuccess("ALICE");

        assertThat(limiter.check("alice", "10.0.0.50").blocked()).isFalse();
        limiter.recordFailure("bob", "10.0.0.1"); // the address's fifth failure, from earlier
        assertThat(limiter.check("carol", "10.0.0.1").blocked()).isTrue();
        // and the username starts again from zero:
        fail("alice", "10.0.0.60", 4);
        assertThat(limiter.check("alice", "10.0.0.61").blocked()).isFalse();
    }

    @Test
    void recordFailureReportsTheMomentTheLimitIsReachedExactlyOnce() {
        boolean[] reached = new boolean[7];
        for (int i = 0; i < 7; i++) {
            reached[i] = limiter.recordFailure("alice", "10.0.0." + i);
        }

        assertThat(reached).containsExactly(false, false, false, false, true, false, false);
    }

    @Test
    void anUnknownAddressIsHandled() {
        fail("alice", null, 5);

        assertThat(limiter.check("alice", null).blocked()).isTrue();
        assertThat(limiter.check("bob", null).blocked()).isTrue(); // all unknown addresses share one counter
    }

    @Test
    void memoryUseIsBoundedWhenManyDifferentNamesAreTried() {
        LoginAttemptLimiter small = new LoginAttemptLimiter(clock,
                new LoginLimitProperties(5, Duration.ofMinutes(15), 100));

        for (int i = 0; i < 20_000; i++) {
            small.recordFailure("spray-" + i, "10.9." + (i % 250) + "." + (i % 200));
        }

        assertThat(small.trackedKeys()).isLessThanOrEqualTo(100);
    }

    @Test
    void expiredEntriesAreDroppedWhenTheyAreLookedAt() {
        fail("alice", "10.0.0.1", 2);
        assertThat(limiter.trackedKeys()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(16));
        limiter.check("alice", "10.0.0.1");

        assertThat(limiter.trackedKeys()).isZero();
    }

    @Test
    void resetForgetsEverything() {
        fail("alice", "10.0.0.1", 5);

        limiter.reset();

        assertThat(limiter.check("alice", "10.0.0.1").blocked()).isFalse();
    }
}
