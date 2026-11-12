package com.contractiq.contract;

import java.time.Instant;

/**
 * A single contract including its full text. Only the detail endpoint returns this.
 */
public record ContractDetailResponse(
        Long id,
        String title,
        String vendorName,
        ContractStatus status,
        String contractText,
        Long createdBy,
        Instant createdAt,
        Instant updatedAt,
        Long version) {

    public static ContractDetailResponse from(Contract contract) {
        return new ContractDetailResponse(
                contract.getId(), contract.getTitle(), contract.getVendorName(), contract.getStatus(),
                contract.getContractText(), contract.getCreatedBy(), contract.getCreatedAt(),
                contract.getUpdatedAt(), contract.getVersion());
    }
}
