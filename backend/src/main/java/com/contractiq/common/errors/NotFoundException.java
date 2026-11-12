package com.contractiq.common.errors;

/**
 * Thrown when a resource does not exist or is not visible to the caller. Using one exception for
 * both cases means the API never reveals that another organization's record exists. Becomes a 404.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
