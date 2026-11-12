package com.contractiq.contract.ai;

/**
 * What is recorded about one AI call attempt. There is deliberately no field for prompts, contract
 * text, answers or provider messages.
 *
 * @param contractId   contract the call was for; null outside an analysis job
 * @param model        model id used
 * @param inputTokens  tokens read, or null if the provider did not report them
 * @param outputTokens tokens written, or null if not reported
 * @param latencyMs    how long the attempt took
 * @param requestId    the provider's request-id header, or null
 * @param outcome      OK, INVALID_OUTPUT, HTTP_429, TIMEOUT, CIRCUIT_OPEN, ... (a short code)
 */
public record AiUsageEntry(Long contractId, String model, Integer inputTokens, Integer outputTokens,
                           long latencyMs, String requestId, String outcome) {
}
