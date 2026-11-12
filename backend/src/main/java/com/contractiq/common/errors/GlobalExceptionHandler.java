package com.contractiq.common.errors;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The only place that turns exceptions into HTTP errors, always as RFC 7807 Problem Details.
 * Messages are written here, never copied from exceptions, so internals, stack traces and
 * submitted values (which could be contract text) never leak to the client or the logs.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** A field that failed validation; only the field name and rule message, never the value. */
    public record FieldError(String field, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldError(e.getField(), e.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(validationProblem(errors));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        return ResponseEntity.badRequest().body(validationProblem(List.of()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldError> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return validationProblem(errors);
    }

    @ExceptionHandler(RequestValidationException.class)
    ProblemDetail handleRequestValidation(RequestValidationException ex) {
        return validationProblem(List.of(new FieldError(ex.getField(), ex.getMessage())));
    }

    /** Failed login. One generic message, so the response never says which part was wrong. */
    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleAuthentication(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid username or password.");
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", "The requested resource was not found.");
    }

    @ExceptionHandler(InvalidTransitionException.class)
    ProblemDetail handleInvalidTransition(InvalidTransitionException ex) {
        return problem(HttpStatus.CONFLICT, "Invalid Status Transition",
                "The contract's current status does not allow this action.");
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "Conflict",
                "The resource was changed by someone else. Reload it and try again.");
    }

    /** Method-level security (@PreAuthorize) throws this inside the controller layer. */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", "You do not have permission to perform this action.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        String errorId = UUID.randomUUID().toString();
        // Log the type and an id only: exception messages can contain submitted data.
        log.error("Unhandled error id={} type={}", errorId, ex.getClass().getName());
        log.debug("Unhandled error id={} details", errorId, ex);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred.");
        problem.setProperty("errorId", errorId);
        return problem;
    }

    private static ProblemDetail validationProblem(List<FieldError> errors) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation Failed", "The request is not valid.");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
