package com.contractiq.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contractiq.common.security.AuthenticatedUser;
import com.contractiq.organization.Organization;
import com.contractiq.support.AbstractMockMvcTest;
import com.contractiq.support.TestData;
import com.contractiq.user.AppUser;
import com.contractiq.user.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tests the login endpoint: success, the single generic failure, and that an unknown username
 * still costs one BCrypt comparison (so response time does not reveal which usernames exist).
 */
class AuthIntegrationTest extends AbstractMockMvcTest {

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    private MvcResult login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn();
    }

    @Test
    void successfulLoginReturnsATokenWithUserOrganizationAndRole() throws Exception {
        Organization org = data.organization();
        AppUser user = data.user(org, Role.REVIEWER);

        MvcResult result = login(user.getUsername(), TestData.PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        var body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("expiresInSeconds").asLong()).isEqualTo(1800);
        AuthenticatedUser parsed = jwtService.parse(body.get("token").asText()).orElseThrow();
        assertThat(parsed.userId()).isEqualTo(user.getId());
        assertThat(parsed.organizationId()).isEqualTo(org.getId());
        assertThat(parsed.role()).isEqualTo(Role.REVIEWER);
    }

    @Test
    void usernameIsMatchedIgnoringCase() throws Exception {
        AppUser user = data.user(data.organization(), Role.UPLOADER);

        assertThat(login(user.getUsername().toUpperCase(), TestData.PASSWORD).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void wrongPasswordAndUnknownUsernameGiveTheSameResponse() throws Exception {
        AppUser user = data.user(data.organization(), Role.UPLOADER);

        MvcResult wrongPassword = login(user.getUsername(), "not-the-password");
        MvcResult unknownUser = login("nobody-" + System.nanoTime(), "not-the-password");

        for (MvcResult result : new MvcResult[] {wrongPassword, unknownUser}) {
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getContentType()).contains("application/problem+json");
        }
        var a = json.readTree(wrongPassword.getResponse().getContentAsString());
        var b = json.readTree(unknownUser.getResponse().getContentAsString());
        assertThat(a.get("title")).isEqualTo(b.get("title"));
        assertThat(a.get("detail")).isEqualTo(b.get("detail"));
        assertThat(a.get("status")).isEqualTo(b.get("status"));
        assertThat(a.get("detail").asText()).isEqualTo("Invalid username or password.");
    }

    @Test
    void unknownUsernameStillComparesAgainstADummyBcryptHash() throws Exception {
        String unknown = "ghost-" + System.nanoTime();
        String probePassword = "probe-password-" + System.nanoTime();

        login(unknown, probePassword);

        // The password was checked once, against a real BCrypt hash, even though no such user exists.
        verify(passwordEncoder).matches(eq(probePassword), org.mockito.ArgumentMatchers.startsWith("$2"));
    }

    @Test
    void knownUserWithWrongPasswordAlsoComparesExactlyOnce() throws Exception {
        AppUser user = data.user(data.organization(), Role.UPLOADER);
        String probePassword = "wrong-" + System.nanoTime();

        login(user.getUsername(), probePassword);

        verify(passwordEncoder).matches(eq(probePassword), anyString());
    }

    @Test
    void blankOrMissingFieldsGive400() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void overlongPasswordIsRejectedBeforeHashing() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "x", "password", "p".repeat(129)))))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ppppp"))));
    }
}
