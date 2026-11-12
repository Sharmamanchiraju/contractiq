package com.contractiq.contract;

import com.contractiq.common.api.PageResponse;
import com.contractiq.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for contracts. Only translates HTTP to service calls and maps results to DTOs;
 * all rules (tenant scoping, status moves, review rules) live in {@link ContractService}.
 */
@RestController
@RequestMapping("/api/v1/contracts")
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('UPLOADER','REVIEWER','ADMIN')")
    public ResponseEntity<ContractSummaryResponse> create(@AuthenticationPrincipal AuthenticatedUser caller,
                                                          @Valid @RequestBody CreateContractRequest request) {
        Contract created = contractService.create(caller, request);
        return ResponseEntity.created(URI.create("/api/v1/contracts/" + created.getId()))
                .body(ContractSummaryResponse.from(created));
    }

    @GetMapping
    public PageResponse<ContractSummaryResponse> list(
            @AuthenticationPrincipal AuthenticatedUser caller,
            @RequestParam(required = false) ContractStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        return ContractService.toSummaryPage(contractService.list(caller, status, page, size));
    }

    @GetMapping("/{id}")
    public ContractDetailResponse get(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        return ContractDetailResponse.from(contractService.get(caller, id));
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    public ReviewResponse review(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id,
                                 @Valid @RequestBody ReviewRequest request) {
        return contractService.review(caller, id, request);
    }
}
