package com.contractiq.contract;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An uploaded contract and its review status. The {@code version} field gives optimistic
 * locking, so two writers changing the same contract cannot silently overwrite each other.
 */
@Entity
@Table(name = "contracts")
public class Contract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "vendor_name", nullable = false, length = 300)
    private String vendorName;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 32)
    private ContractStatus status;

    @Column(name = "contract_text", nullable = false, columnDefinition = "LONGTEXT")
    private String contractText;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Contract() {
        // required by JPA
    }

    public Contract(Long organizationId, String title, String vendorName, String contractText, Long createdBy) {
        this.organizationId = organizationId;
        this.title = title;
        this.vendorName = vendorName;
        this.contractText = contractText;
        this.createdBy = createdBy;
        this.status = ContractStatus.UPLOADED;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public String getTitle() {
        return title;
    }

    public String getVendorName() {
        return vendorName;
    }

    public ContractStatus getStatus() {
        return status;
    }

    public String getContractText() {
        return contractText;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    /**
     * Moves the contract to a new status, or throws if the lifecycle rules forbid it.
     */
    public void moveTo(ContractStatus target) {
        if (!status.canMoveTo(target)) {
            throw new com.contractiq.common.errors.InvalidTransitionException(status, target);
        }
        this.status = target;
    }
}
