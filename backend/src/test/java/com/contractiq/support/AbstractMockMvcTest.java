package com.contractiq.support;

import com.contractiq.common.security.JwtService;
import com.contractiq.user.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for HTTP-level tests: a MockMvc that runs the full security filter chain, the
 * test-only endpoints, and a helper to build a Bearer header for any role.
 */
@AutoConfigureMockMvc
@Import(TestEndpoints.class)
public abstract class AbstractMockMvcTest extends AbstractIntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JwtService jwtService;

    protected String bearer(Role role) {
        return bearer(1L, 1L, role);
    }

    protected String bearer(Long userId, Long organizationId, Role role) {
        return "Bearer " + jwtService.issue(userId, organizationId, role);
    }
}
