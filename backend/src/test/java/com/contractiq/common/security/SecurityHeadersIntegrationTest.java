package com.contractiq.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.contractiq.support.AbstractMockMvcTest;
import com.contractiq.support.TestData;
import com.contractiq.user.AppUser;
import com.contractiq.user.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Checks that the security headers are on every kind of API answer, not only on successes. Several
 * error answers are written by filters, before Spring MVC runs, so they are easy to miss. Spring
 * Security supplies nosniff, X-Frame-Options and no-store by default; the Referrer-Policy and the
 * Content-Security-Policy (with frame-ancestors) are added in SecurityConfig.
 */
class SecurityHeadersIntegrationTest extends AbstractMockMvcTest {

    private void assertSecurityHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeader("X-Content-Type-Options")).as("nosniff").isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).as("framing").isEqualTo("DENY");
        assertThat(response.getHeader("Content-Security-Policy")).as("CSP").contains("frame-ancestors 'none'");
        assertThat(response.getHeader("Referrer-Policy")).as("referrer").isEqualTo("no-referrer");
        assertThat(response.getHeader("Cache-Control")).as("API answers must not be cached").contains("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getHeader("X-Request-Id")).as("request id").isNotBlank();
    }

    private String auth(Role role) {
        return bearer(data.user(data.organization(), role));
    }

    @Test
    void aSuccessfulApiAnswerHasThem() throws Exception {
        assertSecurityHeaders(mvc.perform(get("/api/v1/contracts").header(HttpHeaders.AUTHORIZATION, auth(Role.UPLOADER)))
                .andReturn().getResponse());
    }

    @Test
    void theLoginAnswerHasThem() throws Exception {
        AppUser user = data.user(data.organization(), Role.UPLOADER);
        MockHttpServletResponse response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", user.getUsername(), "password", TestData.PASSWORD))))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        // A token must never be stored by a browser or a proxy.
        assertSecurityHeaders(response);
    }

    @Test
    void a401FromTheFilterHasThem() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/v1/contracts")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertSecurityHeaders(response);
    }

    @Test
    void a403HasThem() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/v1/audit")
                .header(HttpHeaders.AUTHORIZATION, auth(Role.UPLOADER))).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertSecurityHeaders(response);
    }

    @Test
    void a404HasThem() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/v1/contracts/987654321")
                .header(HttpHeaders.AUTHORIZATION, auth(Role.UPLOADER))).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertSecurityHeaders(response);
    }

    @Test
    void a400AndA405HaveThem() throws Exception {
        String token = auth(Role.UPLOADER);

        MockHttpServletResponse bad = mvc.perform(get("/api/v1/contracts?size=0")
                .header(HttpHeaders.AUTHORIZATION, token)).andReturn().getResponse();
        MockHttpServletResponse wrongMethod = mvc.perform(delete("/api/v1/contracts/1")
                .header(HttpHeaders.AUTHORIZATION, token)).andReturn().getResponse();

        assertThat(bad.getStatus()).isEqualTo(400);
        assertThat(wrongMethod.getStatus()).isEqualTo(405);
        assertSecurityHeaders(bad);
        assertSecurityHeaders(wrongMethod);
    }

    @Test
    void aFilterRejectionForATooLargeBodyHasThem() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/v1/contracts")
                .header(HttpHeaders.AUTHORIZATION, auth(Role.UPLOADER))
                .contentType(MediaType.APPLICATION_JSON).content(new byte[1_048_577])).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(413);
        assertSecurityHeaders(response);
    }

    @Test
    void theRateLimitedLoginAnswerHasThem() throws Exception {
        AppUser user = data.user(data.organization(), Role.UPLOADER);
        MockHttpServletResponse last = null;
        for (int i = 0; i < 6; i++) {
            last = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", user.getUsername(), "password", "wrong"))))
                    .andReturn().getResponse();
        }

        assertThat(last.getStatus()).isEqualTo(429);
        assertThat(last.getHeader("Retry-After")).isNotBlank();
        assertSecurityHeaders(last);
    }

    @Test
    void aServerErrorHasThemAndShowsNoStackTrace() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/v1/test/boom")
                .header(HttpHeaders.AUTHORIZATION, auth(Role.UPLOADER))).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(500);
        assertSecurityHeaders(response);
        assertThat(response.getContentAsString()).doesNotContain("at com.contractiq");
    }

    @Test
    void thePublicHealthEndpointHasThemToo() throws Exception {
        assertSecurityHeaders(mvc.perform(get("/actuator/health")).andReturn().getResponse());
    }
}
