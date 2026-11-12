package com.contractiq.common.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contractiq.support.AbstractMockMvcTest;
import com.contractiq.user.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;

/**
 * End-to-end checks of the security rules: 401/403 as Problem Details, public and admin-only
 * actuator endpoints, security headers, CORS and the request size limit.
 */
class SecurityIntegrationTest extends AbstractMockMvcTest {

    @Test
    void noTokenGives401ProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/test/ok"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void garbageTokenGives401() throws Exception {
        mvc.perform(get("/api/v1/test/ok").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenGives401() throws Exception {
        Clock past = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
        JwtService oldIssuer = new JwtService(new JwtProperties(TEST_JWT_SECRET, 30), new MockEnvironment(), past);
        String expired = oldIssuer.issue(1L, 1L, Role.ADMIN);

        mvc.perform(get("/api/v1/test/ok").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithWrongSecretGives401() throws Exception {
        JwtService other = new JwtService(new JwtProperties("a-different-fake-secret-0123456789abcdef", 30),
                new MockEnvironment(), Clock.systemUTC());

        mvc.perform(get("/api/v1/test/ok")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.issue(1L, 1L, Role.ADMIN)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        mvc.perform(get("/api/v1/test/ok").header(HttpHeaders.AUTHORIZATION, bearer(Role.UPLOADER)))
                .andExpect(status().isOk());
    }

    @Test
    void wrongRoleGives403ProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/test/admin-only").header(HttpHeaders.AUTHORIZATION, bearer(Role.UPLOADER)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void rightRoleIsAllowed() throws Exception {
        mvc.perform(get("/api/v1/test/admin-only").header(HttpHeaders.AUTHORIZATION, bearer(Role.ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void healthIsPublicAndShowsNoDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void infoIsPublic() throws Exception {
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    void otherActuatorEndpointsNeedAdmin() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, bearer(Role.UPLOADER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, bearer(Role.REVIEWER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminPassesActuatorSecurityButUnexposedEndpointsAre404() throws Exception {
        // Only health and info are exposed; an ADMIN gets past security and then a 404.
        mvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, bearer(Role.ADMIN)))
                .andExpect(status().isNotFound());
    }

    @Test
    void swaggerAndOpenApiAreOffOutsideTheDevProfile() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    }

    @Test
    void securityHeadersArePresent() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void corsAllowsConfiguredOriginOnly() throws Exception {
        mvc.perform(options("/api/v1/test/ok")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));

        mvc.perform(options("/api/v1/test/ok")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void bodyLargerThanTheLimitGives413() throws Exception {
        byte[] tooBig = new byte[1_048_577];

        mvc.perform(post("/api/v1/test/validate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(Role.UPLOADER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tooBig))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(413));
    }

    @Test
    void chunkedBodyGives411() throws Exception {
        // A chunked request has a Transfer-Encoding header and no declared Content-Length.
        // MockMvc always sets Content-Length when a body is given, so none is given here.
        mvc.perform(post("/api/v1/test/validate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(Role.UPLOADER))
                        .header("Transfer-Encoding", "chunked")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isLengthRequired())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void responsesNeverContainAStackTrace() throws Exception {
        mvc.perform(get("/api/v1/test/ok"))
                .andExpect(content().string(not(containsString("at com.contractiq"))));
    }
}
