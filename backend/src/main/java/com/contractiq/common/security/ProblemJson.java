package com.contractiq.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/**
 * Writes an RFC 7807 Problem Details body straight to the servlet response. Needed for errors
 * raised in filters (401, 403, 413), which happen before Spring MVC's error handler can run.
 */
final class ProblemJson {

    private ProblemJson() {
    }

    static void write(ObjectMapper mapper, HttpServletResponse response, HttpStatus status, String title,
                      String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
