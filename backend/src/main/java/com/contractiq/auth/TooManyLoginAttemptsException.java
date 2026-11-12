package com.contractiq.auth;

/**
 * Thrown when a username or address has failed too many logins. Becomes a 429 with a Retry-After
 * header. The message is the same whichever limit was hit and whether or not the username exists.
 */
public class TooManyLoginAttemptsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyLoginAttemptsException(long retryAfterSeconds) {
        super("Too many failed sign-in attempts");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
