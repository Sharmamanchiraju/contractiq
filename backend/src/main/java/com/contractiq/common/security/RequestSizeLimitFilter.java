package com.contractiq.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects oversized request bodies before they are read. It checks the declared Content-Length
 * (413 if too big). Chunked uploads have no declared length, so they are refused with 411 rather
 * than being allowed to stream an unlimited body.
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ObjectMapper mapper;

    public RequestSizeLimitFilter(long maxBytes, ObjectMapper mapper) {
        this.maxBytes = maxBytes;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            ProblemJson.write(mapper, response, HttpStatus.PAYLOAD_TOO_LARGE, "Payload Too Large",
                    "The request body is larger than the allowed " + maxBytes + " bytes.");
            return;
        }
        if (request.getContentLengthLong() < 0 && request.getHeader("Transfer-Encoding") != null) {
            ProblemJson.write(mapper, response, HttpStatus.LENGTH_REQUIRED, "Length Required",
                    "Requests must declare a Content-Length; chunked bodies are not accepted.");
            return;
        }
        chain.doFilter(request, response);
    }
}
