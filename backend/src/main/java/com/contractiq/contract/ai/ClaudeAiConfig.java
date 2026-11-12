package com.contractiq.contract.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the real Claude analyzer, only when {@code app.ai.provider=claude}. The app refuses to
 * start in that mode without an API key, rather than silently falling back to the mock and
 * pretending to use the real model.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.ai", name = "provider", havingValue = "claude")
@EnableConfigurationProperties(ClaudeProperties.class)
public class ClaudeAiConfig {

    @Bean
    ContractAnalyzer claudeContractAnalyzer(ClaudeProperties properties, ObjectMapper objectMapper,
                                            AiUsageRecorder usageRecorder, MeterRegistry metrics) {
        requireKey(properties);
        return new ClaudeContractAnalyzer(properties, objectMapper, usageRecorder, metrics);
    }

    static void requireKey(ClaudeProperties properties) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY is required when app.ai.provider=claude (use provider 'mock' to run without a key)");
        }
    }
}
