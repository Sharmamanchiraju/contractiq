package com.contractiq.contract;

import java.time.Instant;

/**
 * The saved review plus the contract's new status, returned after a decision.
 */
public record ReviewResponse(
        Long id,
        Long contractId,
        Long reviewerId,
        ReviewDecision decision,
        String comment,
        Instant createdAt,
        ContractStatus contractStatus) {

    public static ReviewResponse from(Review review, ContractStatus contractStatus) {
        return new ReviewResponse(review.getId(), review.getContractId(), review.getReviewerId(),
                review.getDecision(), review.getComment(), review.getCreatedAt(), contractStatus);
    }
}
