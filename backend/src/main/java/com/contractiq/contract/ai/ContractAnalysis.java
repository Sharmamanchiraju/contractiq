package com.contractiq.contract.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * One stored analysis of a contract. Rows are only ever added, never changed, so the history of
 * analyses is kept: {@code analysisVersion} counts 1, 2, ... per contract. The result is held as
 * JSON text in a MySQL JSON column.
 */
@Entity
@Table(name = "contract_analyses")
public class ContractAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false, updatable = false)
    private Long contractId;

    @Column(name = "analysis_version", nullable = false, updatable = false)
    private int analysisVersion;

    @Column(name = "result_json", nullable = false, updatable = false, columnDefinition = "json")
    private String resultJson;

    @Column(name = "provider", nullable = false, updatable = false, length = 20)
    private String provider;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ContractAnalysis() {
        // required by JPA
    }

    public ContractAnalysis(Long contractId, int analysisVersion, String resultJson, String provider) {
        this.contractId = contractId;
        this.analysisVersion = analysisVersion;
        this.resultJson = resultJson;
        this.provider = provider;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public Long getContractId() {
        return contractId;
    }

    public int getAnalysisVersion() {
        return analysisVersion;
    }

    public String getResultJson() {
        return resultJson;
    }

    public String getProvider() {
        return provider;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
