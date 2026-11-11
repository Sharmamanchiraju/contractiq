package com.contractiq.support;

import com.contractiq.common.errors.ConflictException;
import com.contractiq.common.errors.InvalidTransitionException;
import com.contractiq.common.errors.NotFoundException;
import com.contractiq.contract.Contract;
import com.contractiq.contract.ContractStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints whose sole job is to throw each kind of error so the security rules and
 * the global error handler can be tested end to end. Never part of the application.
 */
@RestController
@RequestMapping("/api/v1/test")
public class TestEndpoints {

    /** Request body with one required field, used to trigger a validation error. */
    public record Payload(@NotBlank String name) {
    }

    @GetMapping("/ok")
    public String ok() {
        return "ok";
    }

    @PostMapping("/validate")
    public String validate(@Valid @RequestBody Payload payload) {
        return payload.name();
    }

    @GetMapping("/not-found")
    public String notFound() {
        throw new NotFoundException("internal detail about contract 42");
    }

    @GetMapping("/conflict")
    public String conflict() {
        throw new ConflictException("Already exists.");
    }

    @GetMapping("/invalid-transition")
    public String invalidTransition() {
        throw new InvalidTransitionException(ContractStatus.UPLOADED, ContractStatus.APPROVED);
    }

    @GetMapping("/optimistic-lock")
    public String optimisticLock() {
        throw new ObjectOptimisticLockingFailureException(Contract.class, 1L);
    }

    @GetMapping("/boom")
    public String boom() {
        throw new IllegalStateException("SECRET-INTERNAL-DETAIL jdbc:mysql://host/db password=hunter2");
    }

    @GetMapping("/admin-only")
    @PreAuthorize("hasRole('ADMIN')")
    public String adminOnly() {
        return "admin";
    }
}
