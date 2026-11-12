package com.contractiq.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One line of the audit log: who did what to which thing, and how it ended.
 *
 * <p>The log is append-only. This class has no setters, every column is {@code updatable=false}, and
 * {@link AuditLogRepository} has no update or delete method. The columns are short and fixed-purpose,
 * so there is no place to put a password, a token, contract text or a review comment.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "organization_id", updatable = false)
    private Long organizationId;

    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "action", nullable = false, updatable = false, length = 40)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "entity_type", updatable = false, length = 40)
    private AuditEntityType entityType;

    @Column(name = "entity_id", updatable = false)
    private Long entityId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "outcome", nullable = false, updatable = false, length = 20)
    private AuditOutcome outcome;

    @Column(name = "request_id", updatable = false, length = 64)
    private String requestId;

    @Column(name = "ip_address", updatable = false, length = 45)
    private String ipAddress;

    protected AuditLog() {
        // required by JPA
    }

    public AuditLog(Long organizationId, Long userId, AuditAction action, AuditEntityType entityType,
                    Long entityId, AuditOutcome outcome, String requestId, String ipAddress) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.outcome = outcome;
        this.requestId = truncate(requestId, 64);
        this.ipAddress = truncate(ipAddress, 45);
    }

    @PrePersist
    void onCreate() {
        this.occurredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Long getUserId() {
        return userId;
    }

    public AuditAction getAction() {
        return action;
    }

    public AuditEntityType getEntityType() {
        return entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getIpAddress() {
        return ipAddress;
    }
}
