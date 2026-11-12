package com.contractiq.common.errors;

/**
 * Thrown when a resource does not exist or is not visible to the caller. Using one exception for
 * both cases means the API never reveals that another organization's record exists. Becomes a 404.
 *
 * <p>By default a 404 is written to the audit log (it may be someone probing for other organizations'
 * contracts). A lookup that is expected to come up empty in normal use, such as "no analysis yet",
 * passes {@code audited = false} so it does not flood the log.
 */
public class NotFoundException extends RuntimeException {

    private final boolean audited;

    public NotFoundException(String message) {
        this(message, true);
    }

    public NotFoundException(String message, boolean audited) {
        super(message);
        this.audited = audited;
    }

    public boolean isAudited() {
        return audited;
    }
}
