package com.contractiq.contract;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A reviewer's decision. The reviewer's identity is not part of the body; it comes from the token.
 * A non-blank comment is required when rejecting (checked in the service).
 *
 * @param decision APPROVED or REJECTED
 * @param comment  optional when approving, required when rejecting; at most 2,000 characters
 */
public record ReviewRequest(
        @NotNull ReviewDecision decision,
        @Size(max = 2000) String comment) {
}
