package com.contractiq.contract.ai;

import java.time.Instant;

/**
 * A stored analysis as returned by the API. The result is a draft for a human reviewer: it has
 * no approve or reject outcome.
 *
 * @param contractId the analyzed contract
 * @param version    1 for the first analysis, 2 for the next, and so on
 * @param provider   which analyzer produced it
 * @param createdAt  when it was produced
 * @param result     the extracted facts and risk flags
 */
public record AnalysisResponse(Long contractId, int version, String provider, Instant createdAt,
                               AnalysisResult result) {

    public static AnalysisResponse from(StoredAnalysis stored) {
        return new AnalysisResponse(stored.contractId(), stored.version(), stored.provider(),
                stored.createdAt(), stored.result());
    }
}
