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
 * One row in the AI usage log: a single call attempt with its model, token counts, latency,
 * request id and outcome. Holds no prompts, contract text or provider messages.
 */
@Entity
@Table(name = "ai_usage_log")
public class AiUsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", updatable = false)
    private Long contractId;

    @Column(name = "model", nullable = false, updatable = false, length = 100)
    private String model;

    @Column(name = "input_tokens", updatable = false)
    private Integer inputTokens;

    @Column(name = "output_tokens", updatable = false)
    private Integer outputTokens;

    @Column(name = "latency_ms", nullable = false, updatable = false)
    private long latencyMs;

    @Column(name = "request_id", updatable = false, length = 100)
    private String requestId;

    @Column(name = "outcome", nullable = false, updatable = false, length = 40)
    private String outcome;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiUsageLog() {
        // required by JPA
    }

    public AiUsageLog(AiUsageEntry entry) {
        this.contractId = entry.contractId();
        this.model = truncate(entry.model(), 100);
        this.inputTokens = entry.inputTokens();
        this.outputTokens = entry.outputTokens();
        this.latencyMs = entry.latencyMs();
        this.requestId = truncate(entry.requestId(), 100);
        this.outcome = truncate(entry.outcome(), 40);
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public Long getContractId() {
        return contractId;
    }

    public String getModel() {
        return model;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getOutcome() {
        return outcome;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
