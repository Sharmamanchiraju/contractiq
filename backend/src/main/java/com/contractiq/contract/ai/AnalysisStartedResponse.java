package com.contractiq.contract.ai;

import com.contractiq.contract.ContractStatus;

/**
 * The 202 response to "analyze": the work has been accepted, not finished.
 *
 * @param contractId the contract being analyzed
 * @param status     ANALYZING; poll the contract or the analysis endpoint for the outcome
 */
public record AnalysisStartedResponse(Long contractId, ContractStatus status) {
}
