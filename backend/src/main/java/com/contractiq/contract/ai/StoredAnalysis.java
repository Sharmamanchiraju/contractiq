package com.contractiq.contract.ai;

import java.time.Instant;

/**
 * A stored analysis as the rest of the application sees it: parsed, with no JPA or JSON details.
 *
 * @param id         row id
 * @param contractId the contract analyzed
 * @param version    1 for the first analysis of the contract, 2 for the next, and so on
 * @param result     the validated result
 * @param provider   which analyzer produced it ("mock" or "claude")
 * @param createdAt  when it was stored
 */
public record StoredAnalysis(Long id, Long contractId, int version, AnalysisResult result, String provider,
                             Instant createdAt) {
}
