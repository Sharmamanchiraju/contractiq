package com.contractiq.common.security;

import com.contractiq.audit.AuditAction;
import com.contractiq.audit.AuditOutcome;
import com.contractiq.audit.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Answers 403 with a Problem Details body when a logged-in user lacks the role for a resource.
 */
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper mapper;
    private final AuditService audit;

    public ProblemAccessDeniedHandler(ObjectMapper mapper, AuditService audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        audit.recordForRequest(AuditAction.ACCESS_DENIED, AuditOutcome.DENIED, request.getRequestURI());
        ProblemJson.write(mapper, response, HttpStatus.FORBIDDEN, "Forbidden",
                "You do not have permission to perform this action.");
    }
}
