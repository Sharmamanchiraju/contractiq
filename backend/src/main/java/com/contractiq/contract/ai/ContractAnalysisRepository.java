package com.contractiq.contract.ai;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data access for {@link ContractAnalysis}. Used only through {@link AnalysisStore}.
 */
public interface ContractAnalysisRepository extends JpaRepository<ContractAnalysis, Long> {

    Optional<ContractAnalysis> findFirstByContractIdOrderByAnalysisVersionDesc(Long contractId);

    List<ContractAnalysis> findByContractIdOrderByAnalysisVersionAsc(Long contractId);

    @Query("select coalesce(max(a.analysisVersion), 0) from ContractAnalysis a where a.contractId = :contractId")
    int findMaxVersion(@Param("contractId") Long contractId);
}
