package com.contractiq.common.errors;

/**
 * Thrown when a request clashes with the current state of a resource. Becomes a 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
