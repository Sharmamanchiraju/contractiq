package com.contractiq.contract.ai;

import com.contractiq.common.security.AuthenticatedUser;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for AI analysis. Only translates HTTP to service calls; the rules live in
 * {@link AnalysisService}. There is deliberately no endpoint here that approves or rejects.
 */
@RestController
@RequestMapping("/api/v1/contracts")
public class AnalysisController {

    private final AnalysisService analysisService;

    public AnalysisController(AnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping("/{id}/analyze")
    @PreAuthorize("hasAnyRole('UPLOADER','REVIEWER','ADMIN')")
    public ResponseEntity<AnalysisStartedResponse> analyze(@AuthenticationPrincipal AuthenticatedUser caller,
                                                           @PathVariable Long id) {
        AnalysisStartedResponse started = analysisService.start(caller, id);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/contracts/" + id + "/analysis"))
                .body(started);
    }

    @GetMapping("/{id}/analysis")
    public AnalysisResponse latest(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable Long id) {
        return AnalysisResponse.from(analysisService.getLatest(caller, id));
    }
}
