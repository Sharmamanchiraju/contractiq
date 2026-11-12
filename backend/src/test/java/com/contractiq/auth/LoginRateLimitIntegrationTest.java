package com.contractiq.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.contractiq.organization.Organization;
import com.contractiq.support.AbstractMockMvcTest;
import com.contractiq.support.TestData;
import com.contractiq.user.AppUser;
import com.contractiq.user.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Tests the login rate limit over HTTP: after 5 failures a username or an address gets 429 with
 * Retry-After, a locked name is refused even with the right password, real and made-up usernames
 * behave identically, and a good login resets the username's count.
 */
class LoginRateLimitIntegrationTest extends AbstractMockMvcTest {

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    private MvcResult login(String username, String password, String ip) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn();
    }

    private List<Integer> statuses(String username, String password, String ip, int attempts) throws Exception {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            statuses.add(login(username, password, ip).getResponse().getStatus());
        }
        return statuses;
    }

    @Test
    void fiveFailuresAreAnsweredWith401AndTheSixthWith429AndRetryAfter() throws Exception {
        AppUser user = data.user(data.organization(), Role.UPLOADER);

        List<Integer> statuses = statuses(user.getUsername(), "wrong", "10.20.0.1", 6);

        assertThat(statuses).containsExactly(401, 401, 401, 401, 401, 429);
        MvcResult blocked = login(user.getUsername(), "wrong", "10.20.0.1");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(blocked.getResponse().getContentType()).contains("application/problem+json");
        long retryAfter = Long.parseLong(blocked.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 900L);
        var body = json.readTree(blocked.getResponse().getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("detail").asText()).isEqualTo("Too many failed sign-in attempts. Please try again later.");
    }

    @Test
    void aLockedUsernameIsRefusedEvenWithTheCorrectPassword() throws Exception {
        AppUser user = data.user(data.organization(), Role.UPLOADER);
        statuses(user.getUsername(), "wrong", "10.20.1.1", 5);

        MvcResult attempt = login(user.getUsername(), TestData.PASSWORD, "10.20.1.1");

        assertThat(attempt.getResponse().getStatus()).isEqualTo(429);
        assertThat(attempt.getResponse().getContentAsString()).doesNotContain("token");
        // From another address too, because the username itself is locked.
        assertThat(login(user.getUsername(), TestData.PASSWORD, "10.20.1.2").getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void aMadeUpUsernameBehavesExactlyLikeARealOne() throws Exception {
        AppUser user = data.user(data.organization(), Role.UPLOADER);
        String madeUp = "nobody-" + System.nanoTime();

        List<Integer> real = statuses(user.getUsername(), "wrong", "10.20.2.1", 7);
        List<Integer> fake = statuses(madeUp, "wrong", "10.20.2.2", 7);

        assertThat(real).containsExactly(401, 401, 401, 401, 401, 429, 429);
        assertThat(fake).isEqualTo(real);
        MvcResult realBlocked = login(user.getUsername(), "wrong", "10.20.2.3");
        MvcResult fakeBlocked = login(madeUp, "wrong", "10.20.2.4");
        var a = json.readTree(realBlocked.getResponse().getContentAsString());
        var b = json.readTree(fakeBlocked.getResponse().getContentAsString());
        assertThat(a.get("title")).isEqualTo(b.get("title"));
        assertThat(a.get("detail")).isEqualTo(b.get("detail"));
        assertThat(a.get("status")).isEqualTo(b.get("status"));
        assertThat(realBlocked.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(fakeBlocked.getResponse().getHeader("Retry-After")).isNotNull();
    }

    @Test
    void usernamesAreLockedRegardlessOfCase() throws Exception {
        AppUser user = data.user(data.organization(), Role.UPLOADER);
        String name = user.getUsername();
        for (int i = 0; i < 5; i++) {
            login(i % 2 == 0 ? name.toUpperCase() : name, "wrong", "10.20.3." + i);
        }

        assertThat(login(name, TestData.PASSWORD, "10.20.3.99").getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void anAddressIsLockedByFailuresAgainstManyUsernamesButOtherAddressesAreNot() throws Exception {
        Organization org = data.organization();
        AppUser victim = data.user(org, Role.UPLOADER);
        for (int i = 0; i < 5; i++) {
            login("guess-" + i + "-" + System.nanoTime(), "wrong", "10.20.4.1");
        }

        assertThat(login(victim.getUsername(), TestData.PASSWORD, "10.20.4.1").getResponse().getStatus()).isEqualTo(429);
        assertThat(login(victim.getUsername(), TestData.PASSWORD, "10.20.4.2").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void aSuccessfulLoginResetsTheUsernamesCount() throws Exception {
        AppUser user = data.user(data.organization(), Role.UPLOADER);
        statuses(user.getUsername(), "wrong", "10.20.5.1", 4);
        assertThat(login(user.getUsername(), TestData.PASSWORD, "10.20.5.2").getResponse().getStatus()).isEqualTo(200);

        List<Integer> afterwards = statuses(user.getUsername(), "wrong", "10.20.5.3", 5);

        assertThat(afterwards).containsExactly(401, 401, 401, 401, 401);
    }

    @Test
    void requestsThatFailValidationDoNotCountAsFailedLogins() throws Exception {
        AppUser user = data.user(data.organization(), Role.UPLOADER);
        for (int i = 0; i < 10; i++) {
            MvcResult blank = mvc.perform(post("/api/v1/auth/login")
                    .with(request -> {
                        request.setRemoteAddr("10.20.6.1");
                        return request;
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"\",\"password\":\"\"}")).andReturn();
            assertThat(blank.getResponse().getStatus()).isEqualTo(400);
        }

        assertThat(login(user.getUsername(), TestData.PASSWORD, "10.20.6.1").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void whileLockedNoPasswordIsCheckedSoRefusalsAreCheap() throws Exception {
        AppUser user = data.user(data.organization(), Role.UPLOADER);
        String probe = "probe-password-" + System.nanoTime();

        for (int i = 0; i < 5; i++) {
            login(user.getUsername(), probe, "10.20.7.1");
        }
        for (int i = 0; i < 10; i++) {
            login(user.getUsername(), probe, "10.20.7.1");
        }

        // Five real checks; the ten refused attempts never reached BCrypt.
        verify(passwordEncoder, times(5)).matches(org.mockito.ArgumentMatchers.eq(probe), anyString());
    }
}
