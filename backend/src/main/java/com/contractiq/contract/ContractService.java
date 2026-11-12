package com.contractiq.contract;

import com.contractiq.common.api.PageResponse;
import com.contractiq.common.errors.NotFoundException;
import com.contractiq.common.errors.RequestValidationException;
import com.contractiq.common.security.AuthenticatedUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business rules for contracts. Every query here is scoped by the caller's organization id, so
 * one organization can never read or change another's contracts. A contract in another
 * organization is reported as "not found", exactly like one that does not exist.
 */
@Service
public class ContractService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final ContractRepository contracts;
    private final ReviewRepository reviews;

    public ContractService(ContractRepository contracts, ReviewRepository reviews) {
        this.contracts = contracts;
        this.reviews = reviews;
    }

    /** Stores a new contract in the caller's organization with status UPLOADED. */
    @Transactional
    public Contract create(AuthenticatedUser caller, CreateContractRequest request) {
        Contract contract = new Contract(caller.organizationId(), request.title(), request.vendorName(),
                request.contractText(), caller.userId());
        return contracts.save(contract);
    }

    /**
     * Lists the caller's organization's contracts, newest first, optionally filtered by status.
     * The page size is capped at {@value #MAX_PAGE_SIZE}.
     */
    @Transactional(readOnly = true)
    public Page<Contract> list(AuthenticatedUser caller, ContractStatus status, int page, int size) {
        int cappedSize = Math.min(size, MAX_PAGE_SIZE);
        // id is a tiebreaker so pages stay stable when two contracts share a timestamp.
        Pageable pageable = PageRequest.of(page, cappedSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        if (status == null) {
            return contracts.findByOrganizationId(caller.organizationId(), pageable);
        }
        return contracts.findByOrganizationIdAndStatus(caller.organizationId(), status, pageable);
    }

    /** Returns one contract from the caller's organization, or throws {@link NotFoundException}. */
    @Transactional(readOnly = true)
    public Contract get(AuthenticatedUser caller, Long id) {
        return contracts.findByIdAndOrganizationId(id, caller.organizationId())
                .orElseThrow(() -> new NotFoundException("Contract not found"));
    }

    /**
     * Records a reviewer's decision and moves the contract to APPROVED or REJECTED. The status
     * change and the Review row are saved in one transaction. The status rules decide whether the
     * move is allowed (otherwise 409), and optimistic locking turns a concurrent change into a 409.
     * The reviewer is always the caller from the token.
     */
    @Transactional
    public ReviewResponse review(AuthenticatedUser caller, Long contractId, ReviewRequest request) {
        String comment = request.comment() == null || request.comment().isBlank() ? null : request.comment();
        if (request.decision() == ReviewDecision.REJECTED && comment == null) {
            throw new RequestValidationException("comment", "A comment is required when rejecting a contract.");
        }

        Contract contract = contracts.findByIdAndOrganizationId(contractId, caller.organizationId())
                .orElseThrow(() -> new NotFoundException("Contract not found"));

        contract.moveTo(request.decision() == ReviewDecision.APPROVED
                ? ContractStatus.APPROVED : ContractStatus.REJECTED);
        // Flush the status change first: if someone else changed the contract, the version check
        // fails here and no Review row is written.
        Contract saved = contracts.saveAndFlush(contract);
        Review review = reviews.save(new Review(saved.getId(), caller.userId(), request.decision(), comment));
        return ReviewResponse.from(review, saved.getStatus());
    }

    /** Builds the list response page for the controller. */
    public static PageResponse<ContractSummaryResponse> toSummaryPage(Page<Contract> page) {
        return PageResponse.from(page, ContractSummaryResponse::from);
    }
}
