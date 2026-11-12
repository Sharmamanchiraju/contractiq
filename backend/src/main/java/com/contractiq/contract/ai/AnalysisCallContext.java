package com.contractiq.contract.ai;

import java.util.function.Supplier;

/**
 * Carries the id of the contract being analyzed to the analyzer, so the usage log can say which
 * contract a call was for without changing the {@link ContractAnalyzer} interface. A job runs
 * start to finish on one thread, so a thread-local is safe here; it is always cleared afterwards.
 */
public final class AnalysisCallContext {

    private static final ThreadLocal<Long> CONTRACT_ID = new ThreadLocal<>();

    private AnalysisCallContext() {
    }

    /** Runs the action with the contract id set, then clears it. */
    public static <T> T callWith(Long contractId, Supplier<T> action) {
        Long previous = CONTRACT_ID.get();
        CONTRACT_ID.set(contractId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CONTRACT_ID.remove();
            } else {
                CONTRACT_ID.set(previous);
            }
        }
    }

    /** The contract being analyzed on this thread, or null outside an analysis job. */
    public static Long contractId() {
        return CONTRACT_ID.get();
    }
}
