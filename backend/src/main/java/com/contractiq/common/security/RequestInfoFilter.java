package com.contractiq.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The first filter in the chain. Gives every request a server-made id, returns it in the
 * {@code X-Request-Id} header, adds it to log lines, and remembers it (with the caller's network
 * address) for the audit log. An id sent by the client is ignored, so it cannot be forged to mislead
 * an investigation. Even 401, 403 and 413 answers carry the header, because they pass through here.
 */
public class RequestInfoFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        RequestInfoHolder.set(new RequestInfo(requestId, request.getRemoteAddr()));
        try {
            chain.doFilter(request, response);
        } finally {
            RequestInfoHolder.clear();
            MDC.remove(MDC_KEY);
        }
    }
}
