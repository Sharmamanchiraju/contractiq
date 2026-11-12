package com.contractiq.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of a login call. The password length is capped so a huge value cannot be used to
 * make BCrypt do needless work.
 *
 * @param username the user's login name
 * @param password the plain password; checked against the stored BCrypt hash and never logged
 */
public record LoginRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 128) String password) {
}
