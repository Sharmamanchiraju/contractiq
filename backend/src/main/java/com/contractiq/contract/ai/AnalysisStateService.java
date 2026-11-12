package com.contractiq.contract.ai;

import com.contractiq.audit.AuditAction;
import com.contractiq.audit.AuditEntityType;
import com.contractiq.audit.AuditOutcome;
import com.contractiq.audit.AuditService;
import com.contractiq.contract.Contract;
import com.contractiq.contract.ContractRepository;
import com.contractiq.contract.ContractStatus;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short database steps of an analysis job, each in its own small transaction. The job calls
 * the AI between these steps, so no transaction is ever open during an external call.
 *
 * <p>Every step takes the contract version that was current when the job was started. If the
 * contract has changed since (for example the analysis was restarted after a crash timeout), the
 * job is "superseded": it writes nothing, so an old, slow job cannot overwrite a newer one.
 */
@Service
public class AnalysisStateService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisStateService.class);

    private final ContractRepository contracts;
    private final AnalysisStore store;
    private final AuditService audit;

    public AnalysisStateService(ContractRepository contracts, AnalysisStore store, AuditService audit) {
        this.contracts = contracts;
        this.store = store;
        this.audit = audit;
    }

    /** Returns the text to analyze, or empty if the job is no longer the current one. */
    @Transactional(readOnly = true)
    public Optional<String> loadTextForJob(Long contractId, Long expectedVersion) {
        return contracts.findById(contractId)
                .filter(contract -> isCurrent(contract, expectedVersion))
                .map(Contract::getContractText);
    }

    /**
     * Stores the analysis and moves the contract ANALYZING to ANALYZED, in one transaction.
     * Returns false (and writes nothing) if the job was superseded.
     */
    @Transactional
    public boolean complete(Long contractId, Long expectedVersion, AnalysisResult result, String provider) {
        Contract contract = contracts.findById(contractId).orElse(null);
        if (contract == null || !isCurrent(contract, expectedVersion)) {
            log.info("Analysis result for contract {} discarded: superseded", contractId);
            return false;
        }
        store.save(contractId, result, provider);
        contract.completeAnalysis();
        contracts.saveAndFlush(contract);
        // No user: the job acts on its own. Same transaction as the status change.
        audit.record(AuditAction.ANALYSIS_FINISHED, AuditOutcome.SUCCESS, contract.getOrganizationId(), null,
                AuditEntityType.CONTRACT, contractId);
        return true;
    }

    /**
     * Moves the contract ANALYZING to NEEDS_MANUAL_REVIEW with a safe error code. Runs in its own
     * transaction so it also works when called from the after-commit hook or after a failed step.
     * Returns false (and writes nothing) if the job was superseded.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(Long contractId, Long expectedVersion, AnalysisErrorCode code) {
        Contract contract = contracts.findById(contractId).orElse(null);
        if (contract == null || !isCurrent(contract, expectedVersion)) {
            log.info("Analysis failure for contract {} ignored: superseded", contractId);
            return false;
        }
        contract.failAnalysis(code.name());
        contracts.saveAndFlush(contract);
        audit.record(AuditAction.ANALYSIS_FINISHED, AuditOutcome.FAILURE, contract.getOrganizationId(), null,
                AuditEntityType.CONTRACT, contractId);
        return true;
    }

    private static boolean isCurrent(Contract contract, Long expectedVersion) {
        return contract.getStatus() == ContractStatus.ANALYZING && contract.getVersion().equals(expectedVersion);
    }
}
