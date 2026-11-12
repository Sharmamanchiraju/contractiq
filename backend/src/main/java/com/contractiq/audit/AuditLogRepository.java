package com.contractiq.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Data access for the audit log: add an entry, and read pages of one organization's entries.
 *
 * <p>This interface extends the bare {@link Repository}, not {@code CrudRepository}, so Spring does
 * not add delete or update methods. There must never be one here: a test fails if a method is added
 * whose name suggests changing or removing entries.
 */
public interface AuditLogRepository extends Repository<AuditLog, Long> {

    /** Inserts a new entry. Entries are created with no id, so this can only ever insert. */
    AuditLog save(AuditLog entry);

    Page<AuditLog> findByOrganizationId(Long organizationId, Pageable pageable);

    long countByOrganizationId(Long organizationId);
}
