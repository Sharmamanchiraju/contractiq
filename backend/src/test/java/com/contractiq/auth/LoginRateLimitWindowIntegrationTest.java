package com.contractiq.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.contractiq.support.AbstractMockMvcTest;
import com.contractiq.support.TestData;
import com.contractiq.user.AppUser;
import com.contractiq.user.Role;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** Shows the lock really ends by itself, using a 2-second window so the test does not wait 15 minutes. */
@TestPropertySource(properties = {"app.security.login.window=2s", "app.security.login.max-failures=3"})
class LoginRateLimitWindowIntegrationTest extends AbstractMockMvcTest {

    private int login(String username, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void theLimitAndWindowComeFromConfigurationAndTheLockEndsByItself() throws Exception {
        AppUser user = data.user(data.organization(), Role.UPLOADER);

        for (int i = 0; i < 3; i++) {
            assertThat(login(user.getUsername(), "wrong")).isEqualTo(401);
        }
        assertThat(login(user.getUsername(), TestData.PASSWORD)).isEqualTo(429);

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(login(user.getUsername(), TestData.PASSWORD)).isEqualTo(200));
    }
}
