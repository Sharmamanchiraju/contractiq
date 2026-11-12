package com.contractiq.contract.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Stores analyses in MySQL, one row per version, with the result as JSON. The unique key on
 * (contract id, version) turns an accidental double write into an error instead of a duplicate.
 */
@Component
public class MySqlAnalysisStore implements AnalysisStore {

    private final ContractAnalysisRepository repository;
    private final ObjectMapper objectMapper;

    public MySqlAnalysisStore(ContractAnalysisRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public StoredAnalysis save(Long contractId, AnalysisResult result, String provider) {
        int nextVersion = repository.findMaxVersion(contractId) + 1;
        ContractAnalysis saved = repository.save(
                new ContractAnalysis(contractId, nextVersion, toJson(result), provider));
        return toStored(saved, result);
    }

    @Override
    public Optional<StoredAnalysis> findLatest(Long contractId) {
        return repository.findFirstByContractIdOrderByAnalysisVersionDesc(contractId)
                .map(row -> toStored(row, fromJson(row.getResultJson())));
    }

    private String toJson(AnalysisResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            // Never include the cause: it could echo analysis content.
            throw new IllegalStateException("Could not serialize the analysis result");
        }
    }

    private AnalysisResult fromJson(String json) {
        try {
            return objectMapper.readValue(json, AnalysisResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read a stored analysis result");
        }
    }

    private static StoredAnalysis toStored(ContractAnalysis row, AnalysisResult result) {
        return new StoredAnalysis(row.getId(), row.getContractId(), row.getAnalysisVersion(), result,
                row.getProvider(), row.getCreatedAt());
    }
}
