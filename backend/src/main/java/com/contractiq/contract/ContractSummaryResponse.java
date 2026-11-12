package com.contractiq.contract;

import java.time.Instant;

/**
 * A contract as shown in lists. It deliberately has no contract text: lists stay small and the
 * text is only returned by the detail endpoint.
 */
public record ContractSummaryResponse(
        Long id,
        String title,
        String vendorName,
        ContractStatus status,
        String analysisErrorCode,
        Long createdBy,
        Instant createdAt,
        Instant updatedAt,
        Long version) {

    public static ContractSummaryResponse from(Contract contract) {
        return new ContractSummaryResponse(
                contract.getId(), contract.getTitle(), contract.getVendorName(), contract.getStatus(),
                contract.getAnalysisErrorCode(), contract.getCreatedBy(), contract.getCreatedAt(),
                contract.getUpdatedAt(), contract.getVersion());
    }
}
