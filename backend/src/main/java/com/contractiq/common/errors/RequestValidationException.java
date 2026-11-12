package com.contractiq.common.errors;

/**
 * Thrown by a service when a business-level input rule fails that bean validation cannot express
 * (for example, a comment that is required only for some decisions). Becomes a 400 naming the field.
 */
public class RequestValidationException extends RuntimeException {

    private final String field;

    public RequestValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
