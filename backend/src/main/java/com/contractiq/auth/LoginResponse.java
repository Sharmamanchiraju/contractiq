package com.contractiq.auth;

/**
 * The result of a successful login.
 *
 * @param token            the signed JWT to send as {@code Authorization: Bearer <token>}
 * @param tokenType        always "Bearer"
 * @param expiresInSeconds how long the token is valid
 */
public record LoginResponse(String token, String tokenType, long expiresInSeconds) {
}
