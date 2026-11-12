package com.contractiq.contract.ai;

import com.contractiq.common.errors.NotFoundException;
import com.contractiq.common.security.AuthenticatedUser;
import com.contractiq.contract.Contract;
import com.contractiq.contract.ContractRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Starts analyses and reads their results. Every lookup is scoped to the caller's organization,
 * and a contract in another organization is "not found".
 *
 * <p>Starting is a short transaction: it moves the contract to ANALYZING (the status check plus
 * optimistic locking stop two analyses running at once) and the job is queued only AFTER that
 * transaction has committed, so the worker can never read a stale status.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final ContractRepository contracts;
    private final AnalysisStore store;
    private final AnalysisJobRunner jobRunner;
    private final AiProperties properties;
    private final Clock clock;

    public AnalysisService(ContractRepository contracts, AnalysisStore store, AnalysisJobRunner jobRunner,
                           AiProperties properties, Clock clock) {
        this.contracts = contracts;
        this.store = store;
        this.jobRunner = jobRunner;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Moves the contract to ANALYZING and queues the analysis job once the change is committed.
     * Allowed from UPLOADED, ANALYZED and NEEDS_MANUAL_REVIEW, and from an ANALYZING contract that
     * has been stuck for longer than the stale timeout. Otherwise a 409 results.
     */
    @Transactional
    public AnalysisStartedResponse start(AuthenticatedUser caller, Long contractId) {
        Contract contract = contracts.findByIdAndOrganizationId(contractId, caller.organizationId())
                .orElseThrow(() -> new NotFoundException("Contract not found"));
        contract.beginAnalysis(clock.instant(), properties.staleAnalysisTimeout());
        // Flush now: a concurrent change fails here with an optimistic-lock error (409).
        Contract saved = contracts.saveAndFlush(contract);
        Long version = saved.getVersion();
        log.info("Analysis started for contract {} (version {})", contractId, version);

        runAfterCommit(() -> jobRunner.submit(contractId, version));
        return new AnalysisStartedResponse(contractId, saved.getStatus());
    }

    /** Returns the newest analysis of a contract in the caller's organization, or throws not found. */
    @Transactional(readOnly = true)
    public StoredAnalysis getLatest(AuthenticatedUser caller, Long contractId) {
        contracts.findByIdAndOrganizationId(contractId, caller.organizationId())
                .orElseThrow(() -> new NotFoundException("Contract not found"));
        return store.findLatest(contractId).orElseThrow(() -> new NotFoundException("Analysis not found"));
    }

    private static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
