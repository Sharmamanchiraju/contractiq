package com.contractiq.audit;

import com.contractiq.common.api.PageResponse;
import com.contractiq.common.security.AuthenticatedUser;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only access to the audit log for administrators. Only GET exists: there is deliberately no way
 * to change or delete an entry over HTTP. An administrator sees their own organization's entries only.
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<AuditEntryResponse> list(@AuthenticationPrincipal AuthenticatedUser caller,
                                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "20") @Min(1) int size) {
        return auditService.page(caller, page, size);
    }
}
