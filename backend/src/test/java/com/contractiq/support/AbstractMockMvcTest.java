package com.contractiq.support;

import com.contractiq.auth.LoginAttemptLimiter;
import com.contractiq.common.security.JwtService;
import com.contractiq.user.AppUser;
import com.contractiq.user.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for HTTP-level tests: a MockMvc that runs the full security filter chain, the
 * test-only endpoints, and a helper to build a Bearer header for any role.
 */
@AutoConfigureMockMvc
@Import({TestEndpoints.class, TestData.class})
public abstract class AbstractMockMvcTest extends AbstractIntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected TestData data;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    private LoginAttemptLimiter loginLimiter;

    /** Many tests fail logins from the same default test address; start each one with a clean limiter. */
    @BeforeEach
    void resetLoginLimiter() {
        loginLimiter.reset();
    }

    @Autowired
    protected JwtService jwtService;

    protected String bearer(Role role) {
        return bearer(1L, 1L, role);
    }

    /** A Bearer header for a real user saved in the database. */
    protected String bearer(AppUser user) {
        return bearer(user.getId(), user.getOrganizationId(), user.getRole());
    }

    protected String bearer(Long userId, Long organizationId, Role role) {
        return "Bearer " + jwtService.issue(userId, organizationId, role);
    }
}
