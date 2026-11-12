package com.contractiq.common.security;

import com.contractiq.user.Role;

/**
 * The caller as seen by controllers and services, rebuilt from the JWT claims on every request.
 * Services use {@code organizationId} to keep each tenant's data separate.
 *
 * @param userId         id of the logged-in user
 * @param organizationId organization the user belongs to
 * @param role           the user's single role
 */
public record AuthenticatedUser(Long userId, Long organizationId, Role role) {
}
