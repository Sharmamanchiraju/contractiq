package com.contractiq.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contractiq.organization.OrganizationRepository;
import com.contractiq.support.AbstractMockMvcTest;
import com.contractiq.user.AppUser;
import com.contractiq.user.AppUserRepository;
import com.contractiq.user.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the app with the dev profile against MySQL: the seeder creates the demo organization and
 * users from the configured passwords, stores only BCrypt hashes, does not duplicate on a second
 * run, and Swagger/OpenAPI is switched on.
 */
@ActiveProfiles("dev")
class DevProfileIntegrationTest extends AbstractMockMvcTest {

    private static final String UPLOADER_PASSWORD = "fake-seed-uploader-password";
    private static final String REVIEWER_PASSWORD = "fake-seed-reviewer-password";
    private static final String ADMIN_PASSWORD = "fake-seed-admin-password";

    @DynamicPropertySource
    static void seedPasswords(DynamicPropertyRegistry registry) {
        registry.add("app.seed.uploader-password", () -> UPLOADER_PASSWORD);
        registry.add("app.seed.reviewer-password", () -> REVIEWER_PASSWORD);
        registry.add("app.seed.admin-password", () -> ADMIN_PASSWORD);
    }

    @Autowired
    private AppUserRepository users;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private DevDataSeeder seeder;

    @Test
    void seederCreatesOneOrganizationAndThreeUsersWithHashedPasswords() {
        var organization = organizations.findFirstByName("Demo Organization").orElseThrow();
        for (var entry : Map.of("uploader", Role.UPLOADER, "reviewer", Role.REVIEWER, "admin", Role.ADMIN).entrySet()) {
            AppUser user = users.findByUsername(entry.getKey()).orElseThrow();
            assertThat(user.getRole()).isEqualTo(entry.getValue());
            assertThat(user.getOrganizationId()).isEqualTo(organization.getId());
            assertThat(user.getPasswordHash()).startsWith("$2").doesNotContain("fake-seed");
        }
    }

    @Test
    void runningTheSeederAgainDoesNotDuplicate() {
        long usersBefore = users.count();
        long orgsBefore = organizations.count();

        seeder.run(null);

        assertThat(users.count()).isEqualTo(usersBefore);
        assertThat(organizations.count()).isEqualTo(orgsBefore);
    }

    @Test
    void seededUsersCanLogInWithTheConfiguredPasswords() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "reviewer", "password", REVIEWER_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "reviewer", "password", "wrong"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void openApiDocumentIsAvailableInDev() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("ContractIQ API"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/contracts/{id}/review']").exists());
    }
}
