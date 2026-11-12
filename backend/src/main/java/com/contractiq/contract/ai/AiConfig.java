package com.contractiq.contract.ai;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Creates the bounded thread pool that runs analysis jobs. When the pool and its queue are full,
 * new jobs are rejected (not run on the caller's thread), and the service reports the contract as
 * unavailable instead of piling up work without limit.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    @Bean("analysisExecutor")
    ThreadPoolTaskExecutor analysisExecutor(AiProperties properties) {
        AiProperties.Executor settings = properties.executor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(settings.coreSize());
        executor.setMaxPoolSize(settings.maxSize());
        executor.setQueueCapacity(settings.queueCapacity());
        executor.setThreadNamePrefix("analysis-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
