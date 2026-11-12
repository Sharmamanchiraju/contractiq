package com.contractiq.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one audit entry, in one of two transactional ways. Kept as a separate bean so that Spring's
 * transaction handling applies to each call.
 */
@Service
public class AuditWriter {

    private final AuditLogRepository repository;

    public AuditWriter(AuditLogRepository repository) {
        this.repository = repository;
    }

    /** Joins the caller's transaction: the entry and the business change succeed or fail together. */
    @Transactional
    public void writeInCurrentTransaction(AuditLog entry) {
        repository.save(entry);
    }

    /** Commits on its own, so the entry survives even if the caller's transaction is rolled back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeInNewTransaction(AuditLog entry) {
        repository.save(entry);
    }
}
