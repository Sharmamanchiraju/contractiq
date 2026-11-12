package com.contractiq.contract;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body for uploading a contract. There is no organization or creator field on purpose: both
 * always come from the caller's token, never from the request.
 *
 * @param title        short name of the contract
 * @param vendorName   the other party
 * @param contractText the full text, at most 200,000 characters
 */
public record CreateContractRequest(
        @NotBlank @Size(max = 300) String title,
        @NotBlank @Size(max = 300) String vendorName,
        @NotBlank @Size(max = 200_000) String contractText) {
}
