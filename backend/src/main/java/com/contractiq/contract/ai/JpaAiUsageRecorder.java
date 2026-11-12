package com.contractiq.contract.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes usage entries to MySQL, each in its own small transaction. A failure to write is logged
 * (type only) and swallowed: losing a usage row must never fail an analysis.
 */
@Component
public class JpaAiUsageRecorder implements AiUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(JpaAiUsageRecorder.class);

    private final AiUsageLogRepository repository;

    public JpaAiUsageRecorder(AiUsageLogRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AiUsageEntry entry) {
        try {
            repository.save(new AiUsageLog(entry));
        } catch (RuntimeException e) {
            log.error("Could not write AI usage log type={}", e.getClass().getName());
        }
    }
}
