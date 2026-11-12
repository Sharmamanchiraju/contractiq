package com.contractiq.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contractiq.organization.Organization;
import com.contractiq.support.AbstractMockMvcTest;
import com.contractiq.user.AppUser;
import com.contractiq.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tests GET /api/v1/audit: ADMIN only, own organization only, newest first, paged, capped at 100,
 * and read-only over HTTP.
 */
class AuditApiIntegrationTest extends AbstractMockMvcTest {

    @Autowired
    private AuditService audit;

    @Autowired
    private AuditLogRepository entries;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Test data hands out organization ids and user ids from separate counters that tend to move in step,
     * so a user and their organization often share a number. That once let a query filtering by the
     * wrong id pass by coincidence. Creating extra users here keeps the two sequences apart, so a
     * mix-up between "user id" and "organization id" shows up as a failure.
     */
    @BeforeEach
    void keepUserIdsAwayFromOrganizationIds() {
        Organization spare = data.organization();
        data.user(spare, Role.UPLOADER);
        data.user(spare, Role.UPLOADER);
    }

    private void addEntries(Organization org, int count, long firstEntityId) {
        for (int i = 0; i < count; i++) {
            audit.recordBestEffort(AuditAction.CONTRACT_CREATED, AuditOutcome.SUCCESS, org.getId(), 1L,
                    AuditEntityType.CONTRACT, firstEntityId + i);
        }
    }

    @Test
    void anonymousCallersGet401() throws Exception {
        mvc.perform(get("/api/v1/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    void uploadersAndReviewersGet403() throws Exception {
        Organization org = data.organization();
        for (Role role : new Role[] {Role.UPLOADER, Role.REVIEWER}) {
            mvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, bearer(data.user(org, role))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    @Test
    void anAdminSeesTheirOrganizationsEntries() throws Exception {
        Organization org = data.organization();
        AppUser admin = data.user(org, Role.ADMIN);
        assertThat(admin.getId()).as("a user id must not equal its organization id in this test")
                .isNotEqualTo(org.getId());
        addEntries(org, 3, 5_000);

        mvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].action").value("CONTRACT_CREATED"))
                .andExpect(jsonPath("$.items[0].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.items[0].entityType").value("CONTRACT"))
                .andExpect(jsonPath("$.items[0].entityId").value(5_002))
                .andExpect(jsonPath("$.items[0].occurredAt").isNotEmpty())
                .andExpect(jsonPath("$.items[0].organizationId").doesNotExist());
    }

    @Test
    void anotherOrganizationsAdminNeverSeesTheseEntries() throws Exception {
        Organization orgA = data.organization();
        Organization orgB = data.organization();
        AppUser adminA = data.user(orgA, Role.ADMIN);
        AppUser adminB = data.user(orgB, Role.ADMIN);
        addEntries(orgA, 4, 6_000);
        addEntries(orgB, 2, 7_000);

        String bodyA = mvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, bearer(adminA)))
                .andExpect(jsonPath("$.totalElements").value(4)).andReturn().getResponse().getContentAsString();
        String bodyB = mvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, bearer(adminB)))
                .andExpect(jsonPath("$.totalElements").value(2)).andReturn().getResponse().getContentAsString();

        assertThat(bodyA).contains("6000", "6003").doesNotContain("7000").doesNotContain("7001");
        assertThat(bodyB).contains("7000", "7001").doesNotContain("6000").doesNotContain("6003");
    }

    @Test
    void entriesWithNoOrganizationAreNotVisibleToAnyAdmin() throws Exception {
        Organization org = data.organization();
        AppUser admin = data.user(org, Role.ADMIN);
        audit.recordBestEffort(AuditAction.LOGIN_FAILURE, AuditOutcome.FAILURE, null, null, null, null);

        mvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void entriesAreNewestFirstWithAPageSizeOf20ByDefaultAndACapOf100() throws Exception {
        Organization org = data.organization();
        AppUser admin = data.user(org, Role.ADMIN);
        addEntries(org, 130, 10_000);
        String auth = bearer(admin);

        mvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(130))
                .andExpect(jsonPath("$.items[0].entityId").value(10_129))
                .andExpect(jsonPath("$.items[19].entityId").value(10_110));
        mvc.perform(get("/api/v1/audit?size=500").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.items", hasSize(100)));
        mvc.perform(get("/api/v1/audit?page=1&size=100").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.items", hasSize(30)))
                .andExpect(jsonPath("$.items[29].entityId").value(10_000));
    }

    @Test
    void invalidPagingGives400() throws Exception {
        String auth = bearer(data.user(data.organization(), Role.ADMIN));

        mvc.perform(get("/api/v1/audit?size=0").header(HttpHeaders.AUTHORIZATION, auth)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/audit?page=-1").header(HttpHeaders.AUTHORIZATION, auth)).andExpect(status().isBadRequest());
    }

    @Test
    void thereIsNoHttpWayToChangeOrDeleteEntries() throws Exception {
        Organization org = data.organization();
        String auth = bearer(data.user(org, Role.ADMIN));
        addEntries(org, 2, 20_000);
        long before = entries.countByOrganizationId(org.getId());
        Long someId = jdbc.queryForObject("SELECT MIN(id) FROM audit_log WHERE organization_id = ?", Long.class, org.getId());

        for (String path : new String[] {"/api/v1/audit", "/api/v1/audit/" + someId}) {
            mvc.perform(delete(path).header(HttpHeaders.AUTHORIZATION, auth)).andExpect(status().is4xxClientError());
            mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, auth).contentType("application/json").content("{}"))
                    .andExpect(status().is4xxClientError());
            mvc.perform(patch(path).header(HttpHeaders.AUTHORIZATION, auth).contentType("application/json").content("{}"))
                    .andExpect(status().is4xxClientError());
            mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, auth).contentType("application/json").content("{}"))
                    .andExpect(status().is4xxClientError());
        }

        assertThat(entries.countByOrganizationId(org.getId())).isEqualTo(before);
    }

    @Test
    void everyAnswerCarriesAFreshServerMadeRequestId() throws Exception {
        String auth = bearer(data.user(data.organization(), Role.ADMIN));

        String first = mvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(header().exists("X-Request-Id")).andReturn().getResponse().getHeader("X-Request-Id");
        String second = mvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, auth)
                        .header("X-Request-Id", "client-chosen-id"))
                .andReturn().getResponse().getHeader("X-Request-Id");
        String unauthenticated = mvc.perform(get("/api/v1/audit"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getHeader("X-Request-Id");

        assertThat(first).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(second).isNotEqualTo("client-chosen-id").isNotEqualTo(first);
        assertThat(unauthenticated).isNotNull().isNotEqualTo(first);
    }
}
