package com.contractiq.audit;

/**
 * What happened. The decision of a review is part of the action name, so the reviewer's comment never
 * needs to be copied into the audit log.
 */
public enum AuditAction {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    /** A username or address reached the failed-login limit and is now locked out for a while. */
    LOGIN_BLOCKED,
    CONTRACT_CREATED,
    ANALYSIS_STARTED,
    ANALYSIS_FINISHED,
    REVIEW_APPROVED,
    REVIEW_REJECTED,
    /** A logged-in user tried something their role does not allow (HTTP 403). */
    ACCESS_DENIED,
    /** A contract was asked for that is not in the caller's organization (HTTP 404). */
    RESOURCE_NOT_FOUND
}
