package com.contractiq.common.errors;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contractiq.support.AbstractMockMvcTest;
import com.contractiq.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Checks that every error becomes an RFC 7807 Problem Details response with the right status
 * and that internal details never reach the client.
 */
class ErrorHandlingIntegrationTest extends AbstractMockMvcTest {

    private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

    @Test
    void validationErrorGives400WithFieldNamesOnly() throws Exception {
        mvc.perform(post("/api/v1/test/validate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(Role.UPLOADER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void malformedJsonGives400() throws Exception {
        mvc.perform(post("/api/v1/test/validate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(Role.UPLOADER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    @Test
    void notFoundGives404WithoutInternalDetail() throws Exception {
        mvc.perform(get("/api/v1/test/not-found").header(HttpHeaders.AUTHORIZATION, bearer(Role.UPLOADER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(content().string(not(containsString("contract 42"))));
    }

    @Test
    void unknownPathGives404ProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist").header(HttpHeaders.AUTHORIZATION, bearer(Role.UPLOADER)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    @Test
    void wrongMethodGives405ProblemDetails() throws Exception {
        mvc.perform(post("/api/v1/test/ok").header(HttpHeaders.AUTHORIZATION, bearer(Role.UPLOADER)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    @Test
    void conflictGives409() throws Exception {
        mvc.perform(get("/api/v1/test/conflict").header(HttpHeaders.AUTHORIZATION, bearer(Role.UPLOADER)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void invalidTransitionGives409() throws Exception {
        mvc.perform(get("/api/v1/test/invalid-transition").header(HttpHeaders.AUTHORIZATION, bearer(Role.UPLOADER)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.title").value("Invalid Status Transition"));
    }

    @Test
    void optimisticLockFailureGives409() throws Exception {
        mvc.perform(get("/api/v1/test/optimistic-lock").header(HttpHeaders.AUTHORIZATION, bearer(Role.UPLOADER)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    @Test
    void methodSecurityDenialGives403NotAGeneric500() throws Exception {
        mvc.perform(get("/api/v1/test/admin-only").header(HttpHeaders.AUTHORIZATION, bearer(Role.REVIEWER)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    @Test
    void unexpectedErrorGives500WithNoLeakedDetail() throws Exception {
        mvc.perform(get("/api/v1/test/boom").header(HttpHeaders.AUTHORIZATION, bearer(Role.UPLOADER)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.errorId").exists())
                .andExpect(content().string(not(containsString("SECRET-INTERNAL-DETAIL"))))
                .andExpect(content().string(not(containsString("hunter2"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }
}
