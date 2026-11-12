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
import com.contractiq.common.errors.ConflictException;
import com.contractiq.common.errors.InvalidTransitionException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    /** Safe code explaining why the last analysis failed; null when it did not fail. */
    @Column(name = "analysis_error_code", length = 40)
    private String analysisErrorCode;

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
        Instant now = now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = now();
    }

    /** The current time cut to microseconds, the precision of the DATETIME(6) columns. */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
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

    public String getAnalysisErrorCode() {
        return analysisErrorCode;
    }

    /**
     * Starts an analysis: moves the contract to ANALYZING and clears any earlier failure code.
     * Allowed from UPLOADED, ANALYZED and NEEDS_MANUAL_REVIEW (the status rules decide).
     * A contract already ANALYZING normally refuses with a conflict, because another analysis is
     * running. If it has been ANALYZING for longer than {@code staleAfter}, the earlier run is
     * presumed dead (for example the app crashed), and a new analysis may start.
     */
    public void beginAnalysis(Instant now, Duration staleAfter) {
        if (status == ContractStatus.ANALYZING) {
            if (updatedAt.plus(staleAfter).isAfter(now)) {
                throw new ConflictException("An analysis is already in progress for this contract.");
            }
            // Restart: refreshing updatedAt makes the row change, so the version moves on and
            // any older, still-running job is recognised as superseded.
            this.updatedAt = now.truncatedTo(ChronoUnit.MICROS);
            this.analysisErrorCode = null;
            return;
        }
        moveTo(ContractStatus.ANALYZING);
        this.analysisErrorCode = null;
    }

    /** Records a finished analysis: ANALYZING to ANALYZED. Nothing here can approve or reject. */
    public void completeAnalysis() {
        requireAnalyzing(ContractStatus.ANALYZED);
        moveTo(ContractStatus.ANALYZED);
        this.analysisErrorCode = null;
    }

    /** Records a failed analysis: ANALYZING to NEEDS_MANUAL_REVIEW, with a safe error code. */
    public void failAnalysis(String safeErrorCode) {
        requireAnalyzing(ContractStatus.NEEDS_MANUAL_REVIEW);
        moveTo(ContractStatus.NEEDS_MANUAL_REVIEW);
        this.analysisErrorCode = safeErrorCode;
    }

    /**
     * The analysis job may only finish a contract that is still ANALYZING. The general status
     * rules would also allow, say, ANALYZED to NEEDS_MANUAL_REVIEW, but that is not the job's call.
     */
    private void requireAnalyzing(ContractStatus target) {
        if (status != ContractStatus.ANALYZING) {
            throw new InvalidTransitionException(status, target);
        }
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
