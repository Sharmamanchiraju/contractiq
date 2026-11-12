package com.contractiq.contract.ai;

import java.util.Optional;

/**
 * Where analyses are kept. The application depends on this interface, so the MySQL JSON-column
 * implementation could later be replaced (for example by a document store) without touching callers.
 */
public interface AnalysisStore {

    /**
     * Stores a new analysis as the next version for the contract (1, 2, ...). Call it inside the same
     * transaction that updates the contract, so the two succeed or fail together.
     */
    StoredAnalysis save(Long contractId, AnalysisResult result, String provider);

    /** Returns the newest analysis of the contract, if any. */
    Optional<StoredAnalysis> findLatest(Long contractId);
}
