package com.contractiq.contract.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for AI analysis, bound from {@code app.ai.*}.
 *
 * @param provider             "mock" or "claude"
 * @param staleAnalysisTimeout how long a contract may stay ANALYZING before it may be re-analyzed
 *                             (crash recovery)
 * @param redaction            redaction settings
 * @param executor             settings of the bounded thread pool that runs analysis jobs
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        @DefaultValue("mock") String provider,
        @DefaultValue("10m") Duration staleAnalysisTimeout,
        @DefaultValue Redaction redaction,
        @DefaultValue Executor executor) {

    public AiProperties {
        if (!"mock".equals(provider) && !"claude".equals(provider)) {
            throw new IllegalArgumentException("app.ai.provider must be 'mock' or 'claude'");
        }
    }

    /** @param enabled whether personal-data patterns are masked before text leaves the system */
    public record Redaction(@DefaultValue("true") boolean enabled) {
    }

    /**
     * @param coreSize      threads kept running
     * @param maxSize       most threads ever running at once
     * @param queueCapacity jobs that may wait; beyond this, new analyses are refused as unavailable
     */
    public record Executor(
            @DefaultValue("2") int coreSize,
            @DefaultValue("4") int maxSize,
            @DefaultValue("25") int queueCapacity) {
    }
}
