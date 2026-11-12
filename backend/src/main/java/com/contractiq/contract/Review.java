package com.contractiq.contract;

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
 * The record of one human decision on a contract: who decided, what, and why.
 */
@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "reviewer_id", nullable = false)
    private Long reviewerId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "decision", nullable = false, length = 16)
    private ReviewDecision decision;

    @Column(name = "comment", length = 2000)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Review() {
        // required by JPA
    }

    public Review(Long contractId, Long reviewerId, ReviewDecision decision, String comment) {
        this.contractId = contractId;
        this.reviewerId = reviewerId;
        this.decision = decision;
        this.comment = comment;
    }

    @PrePersist
    void onCreate() {
        // Microseconds, the precision of the DATETIME(6) column, so the response matches what is stored.
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getContractId() {
        return contractId;
    }

    public Long getReviewerId() {
        return reviewerId;
    }

    public ReviewDecision getDecision() {
        return decision;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
