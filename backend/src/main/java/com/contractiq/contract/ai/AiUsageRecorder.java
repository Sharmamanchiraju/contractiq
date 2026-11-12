package com.contractiq.contract.ai;

/** Where AI call attempts are recorded. Recording must never make an analysis fail. */
public interface AiUsageRecorder {

    void record(AiUsageEntry entry);
}
