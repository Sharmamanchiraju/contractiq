package com.contractiq.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the application's analyzer with a scriptable one in tests that need to control what
 * the "AI" returns. {@code @Primary} makes it win over the real mock analyzer bean.
 */
@TestConfiguration
public class ControllableAnalyzerConfig {

    @Bean
    @Primary
    ControllableAnalyzer controllableAnalyzer() {
        return new ControllableAnalyzer();
    }
}
