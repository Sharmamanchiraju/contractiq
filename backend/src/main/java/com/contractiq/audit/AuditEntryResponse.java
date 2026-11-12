package com.contractiq.audit;

import java.time.Instant;

/**
 * One audit entry as returned to an administrator of the same organization.
 *
 * @param id         entry number (increases over time)
 * @param occurredAt when it happened (UTC)
 * @param userId     who did it, or null if unknown or not a person (for example the analysis job)
 * @param action     what happened
 * @param entityType what kind of thing it was about, or null
 * @param entityId   which one, or null
 * @param outcome    how it ended
 * @param requestId  the id of the HTTP request, for matching with the application log
 * @param ipAddress  the network address the request came from (personal data: handle accordingly)
 */
public record AuditEntryResponse(Long id, Instant occurredAt, Long userId, AuditAction action,
                                 AuditEntityType entityType, Long entityId, AuditOutcome outcome,
                                 String requestId, String ipAddress) {

    public static AuditEntryResponse from(AuditLog entry) {
        return new AuditEntryResponse(entry.getId(), entry.getOccurredAt(), entry.getUserId(), entry.getAction(),
                entry.getEntityType(), entry.getEntityId(), entry.getOutcome(), entry.getRequestId(),
                entry.getIpAddress());
    }
}
