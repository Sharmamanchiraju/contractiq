package com.contractiq.user;

/**
 * The roles a user can have. Each user has exactly one; authorization rules refer to these names.
 */
public enum Role {
    UPLOADER,
    REVIEWER,
    ADMIN
}
