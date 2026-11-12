package com.contractiq.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contractiq.contract.Contract;
import com.contractiq.contract.ContractStatus;
import com.contractiq.contract.ai.AnalysisErrorCode;
import com.contractiq.contract.ai.AnalysisException;
import com.contractiq.organization.Organization;
import com.contractiq.support.AbstractAnalysisTest;
import com.contractiq.support.TestData;
import com.contractiq.user.AppUser;
import com.contractiq.user.Role;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Checks that every audited action writes exactly the right row (who, what, which thing, how it
 * ended, which request, which address), that refusals and misses are recorded, that nothing sensitive
 * ever reaches the table, and that harmless lookups do not flood it.
 */
class AuditLogIntegrationTest extends AbstractAnalysisTest {

    private static final String CLIENT_IP = "203.0.113.7";

    private List<Map<String, Object>> rowsOf(Organization org) {
        return jdbc.queryForList("SELECT * FROM audit_log WHERE organization_id = ? ORDER BY id", org.getId());
    }

    private List<Map<String, Object>> rowsOf(Organization org, AuditAction action) {
        return jdbc.queryForList("SELECT * FROM audit_log WHERE organization_id = ? AND action = ? ORDER BY id",
                org.getId(), action.name());
    }

    private List<Map<String, Object>> rowsForRequest(String requestId) {
        return jdbc.queryForList("SELECT * FROM audit_log WHERE request_id = ? ORDER BY id", requestId);
    }

    private MvcResult login(String username, String password, String ip) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password)))).andReturn();
    }

    private static String requestId(MvcResult result) {
        return result.getResponse().getHeader("X-Request-Id");
    }

    private ResultActionsHolder createContract(String auth, String title, String text) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/contracts").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("title", title, "vendorName", "Vendor", "contractText", text))))
                .andExpect(status().isCreated()).andReturn();
        return new ResultActionsHolder(json.readTree(result.getResponse().getContentAsString()).get("id").asLong(),
                requestId(result));
    }

    private record ResultActionsHolder(Long contractId, String requestId) {
    }

    private void review(String auth, Long contractId, String body) throws Exception {
        mvc.perform(post("/api/v1/contracts/" + contractId + "/review").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---- logins ----

    @Test
    void aSuccessfulLoginIsRecordedWithTheUserTheRequestIdAndTheAddress() throws Exception {
        Organization org = data.organization();
        AppUser user = data.user(org, Role.REVIEWER);

        MvcResult result = login(user.getUsername(), TestData.PASSWORD, CLIENT_IP);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(rowsOf(org)).singleElement().satisfies(row -> {
            assertThat(row.get("action")).isEqualTo("LOGIN_SUCCESS");
            assertThat(row.get("outcome")).isEqualTo("SUCCESS");
            assertThat(row.get("user_id")).isEqualTo(user.getId());
            assertThat(row.get("entity_type")).isEqualTo("USER");
            assertThat(row.get("entity_id")).isEqualTo(user.getId());
            assertThat(row.get("ip_address")).isEqualTo(CLIENT_IP);
            assertThat(row.get("request_id")).isEqualTo(requestId(result));
            assertThat(row.get("occurred_at")).isNotNull();
        });
    }

    @Test
    void aFailedLoginForARealUserIsRecordedAgainstThatUser() throws Exception {
        Organization org = data.organization();
        AppUser user = data.user(org, Role.UPLOADER);

        MvcResult result = login(user.getUsername(), "wrong-password", CLIENT_IP);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(rowsOf(org)).singleElement().satisfies(row -> {
            assertThat(row.get("action")).isEqualTo("LOGIN_FAILURE");
            assertThat(row.get("outcome")).isEqualTo("FAILURE");
            assertThat(row.get("user_id")).isEqualTo(user.getId());
            assertThat(row.get("ip_address")).isEqualTo(CLIENT_IP);
            assertThat(row.get("request_id")).isEqualTo(requestId(result));
        });
    }

    @Test
    void aFailedLoginForAnUnknownNameIsRecordedWithNoUserAndWithoutTheTypedName() throws Exception {
        String typed = "typed-name-" + System.nanoTime();

        MvcResult result = login(typed, "wrong-password", CLIENT_IP);

        assertThat(rowsForRequest(requestId(result))).singleElement().satisfies(row -> {
            assertThat(row.get("action")).isEqualTo("LOGIN_FAILURE");
            assertThat(row.get("organization_id")).isNull();
            assertThat(row.get("user_id")).isNull();
            assertThat(row.get("entity_type")).isNull();
            assertThat(row.get("ip_address")).isEqualTo(CLIENT_IP);
            assertThat(row.values().stream().map(String::valueOf)).noneMatch(value -> value.contains(typed));
        });
    }

    @Test
    void reachingTheFailedLoginLimitIsRecordedOnceAndFurtherRefusalsAddNoRows() throws Exception {
        Organization org = data.organization();
        AppUser user = data.user(org, Role.UPLOADER);

        for (int i = 0; i < 5; i++) {
            login(user.getUsername(), "wrong", CLIENT_IP);
        }
        assertThat(rowsOf(org, AuditAction.LOGIN_FAILURE)).hasSize(5);
        assertThat(rowsOf(org, AuditAction.LOGIN_BLOCKED)).hasSize(1);
        long rowsWhenLocked = rowsOf(org).size();

        for (int i = 0; i < 20; i++) {
            assertThat(login(user.getUsername(), "wrong", CLIENT_IP).getResponse().getStatus()).isEqualTo(429);
        }

        assertThat(rowsOf(org)).hasSize((int) rowsWhenLocked);
    }

    // ---- contracts, analysis, reviews ----

    @Test
    void creatingAContractIsRecorded() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);

        ResultActionsHolder created = createContract(bearer(uploader), "A title", "Some text");

        assertThat(rowsOf(org, AuditAction.CONTRACT_CREATED)).singleElement().satisfies(row -> {
            assertThat(row.get("outcome")).isEqualTo("SUCCESS");
            assertThat(row.get("user_id")).isEqualTo(uploader.getId());
            assertThat(row.get("entity_type")).isEqualTo("CONTRACT");
            assertThat(row.get("entity_id")).isEqualTo(created.contractId());
            assertThat(row.get("request_id")).isEqualTo(created.requestId());
            assertThat(row.get("ip_address")).isEqualTo("127.0.0.1");
        });
    }

    @Test
    void startingAndFinishingAnAnalysisAreBothRecorded() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        String marker = marker();
        Contract contract = contractWithText(org, uploader, ContractStatus.UPLOADED, marker + ". Plain text.");
        analyzer.script(marker);

        MvcResult started = analyze(bearer(uploader), contract.getId()).andExpect(status().isAccepted()).andReturn();
        awaitStatus(contract.getId(), ContractStatus.ANALYZED);
        awaitIdle();

        assertThat(rowsOf(org, AuditAction.ANALYSIS_STARTED)).singleElement().satisfies(row -> {
            assertThat(row.get("user_id")).isEqualTo(uploader.getId());
            assertThat(row.get("entity_id")).isEqualTo(contract.getId());
            assertThat(row.get("request_id")).isEqualTo(requestId(started));
        });
        assertThat(rowsOf(org, AuditAction.ANALYSIS_FINISHED)).singleElement().satisfies(row -> {
            assertThat(row.get("outcome")).isEqualTo("SUCCESS");
            assertThat(row.get("user_id")).as("the job is not a person").isNull();
            assertThat(row.get("entity_type")).isEqualTo("CONTRACT");
            assertThat(row.get("entity_id")).isEqualTo(contract.getId());
            assertThat(row.get("request_id")).as("the job runs outside any web request").isNull();
        });
    }

    @Test
    void aFailedAnalysisIsRecordedAsAFailureWithoutAnyErrorText() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        String marker = marker();
        Contract contract = contractWithText(org, uploader, ContractStatus.UPLOADED, marker + ". Plain text.");
        analyzer.script(marker).thenThrow(new AnalysisException(AnalysisErrorCode.ANALYSIS_PROVIDER_ERROR,
                "safe", new RuntimeException("RAW-PROVIDER-MESSAGE-SECRET")));

        analyze(bearer(uploader), contract.getId()).andExpect(status().isAccepted());
        awaitStatus(contract.getId(), ContractStatus.NEEDS_MANUAL_REVIEW);
        awaitIdle();

        assertThat(rowsOf(org, AuditAction.ANALYSIS_FINISHED)).singleElement()
                .satisfies(row -> assertThat(row.get("outcome")).isEqualTo("FAILURE"));
        assertThat(rowsOf(org).toString()).doesNotContain("RAW-PROVIDER-MESSAGE-SECRET").doesNotContain("PROVIDER_ERROR");
    }

    @Test
    void approvingAndRejectingAreRecordedAsDifferentActionsUnderTheReviewersName() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        AppUser reviewer = data.user(org, Role.REVIEWER);
        Contract toApprove = data.contract(org, uploader, ContractStatus.ANALYZED);
        Contract toReject = data.contract(org, uploader, ContractStatus.ANALYZED);

        review(bearer(reviewer), toApprove.getId(), "{\"decision\":\"APPROVED\"}");
        review(bearer(reviewer), toReject.getId(), "{\"decision\":\"REJECTED\",\"comment\":\"Not acceptable.\"}");

        assertThat(rowsOf(org, AuditAction.REVIEW_APPROVED)).singleElement().satisfies(row -> {
            assertThat(row.get("user_id")).isEqualTo(reviewer.getId());
            assertThat(row.get("entity_id")).isEqualTo(toApprove.getId());
            assertThat(row.get("outcome")).isEqualTo("SUCCESS");
        });
        assertThat(rowsOf(org, AuditAction.REVIEW_REJECTED)).singleElement()
                .satisfies(row -> assertThat(row.get("entity_id")).isEqualTo(toReject.getId()));
    }

    @Test
    void aReviewThatFailsLeavesNoReviewRow() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        AppUser reviewer = data.user(org, Role.REVIEWER);
        Contract uploaded = data.contract(org, uploader, ContractStatus.UPLOADED);
        Contract analyzed = data.contract(org, uploader, ContractStatus.ANALYZED);

        review(bearer(reviewer), uploaded.getId(), "{\"decision\":\"APPROVED\"}");                 // 409: wrong status
        review(bearer(reviewer), analyzed.getId(), "{\"decision\":\"REJECTED\"}");                 // 400: comment missing

        assertThat(rowsOf(org, AuditAction.REVIEW_APPROVED)).isEmpty();
        assertThat(rowsOf(org, AuditAction.REVIEW_REJECTED)).isEmpty();
    }

    // ---- refusals and misses ----

    @Test
    void aRefusedActionIsRecordedAsAccessDeniedWithTheContractId() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        Contract contract = data.contract(org, uploader, ContractStatus.ANALYZED);

        MvcResult denied = mvc.perform(post("/api/v1/contracts/" + contract.getId() + "/review")
                        .header(HttpHeaders.AUTHORIZATION, bearer(uploader))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isForbidden()).andReturn();

        assertThat(rowsOf(org, AuditAction.ACCESS_DENIED)).singleElement().satisfies(row -> {
            assertThat(row.get("outcome")).isEqualTo("DENIED");
            assertThat(row.get("user_id")).isEqualTo(uploader.getId());
            assertThat(row.get("entity_type")).isEqualTo("CONTRACT");
            assertThat(row.get("entity_id")).isEqualTo(contract.getId());
            assertThat(row.get("request_id")).isEqualTo(requestId(denied));
        });
    }

    @Test
    void aRefusalByTheUrlRulesIsRecordedToo() throws Exception {
        Organization org = data.organization();
        AppUser reviewer = data.user(org, Role.REVIEWER);

        mvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, bearer(reviewer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, bearer(reviewer)))
                .andExpect(status().isForbidden());

        assertThat(rowsOf(org, AuditAction.ACCESS_DENIED)).hasSize(2)
                .allSatisfy(row -> {
                    assertThat(row.get("user_id")).isEqualTo(reviewer.getId());
                    assertThat(row.get("entity_type")).isNull();
                });
    }

    @Test
    void askingForAnotherOrganizationsContractIsRecordedAgainstTheAskerNotTheOwner() throws Exception {
        Organization orgA = data.organization();
        Organization orgB = data.organization();
        AppUser uploaderA = data.user(orgA, Role.UPLOADER);
        AppUser reviewerB = data.user(orgB, Role.REVIEWER);
        Contract contractOfA = data.contract(orgA, uploaderA, ContractStatus.ANALYZED);

        MvcResult read = mvc.perform(get("/api/v1/contracts/" + contractOfA.getId())
                .header(HttpHeaders.AUTHORIZATION, bearer(reviewerB))).andExpect(status().isNotFound()).andReturn();
        mvc.perform(post("/api/v1/contracts/" + contractOfA.getId() + "/review")
                .header(HttpHeaders.AUTHORIZATION, bearer(reviewerB))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isNotFound());

        assertThat(rowsOf(orgB, AuditAction.RESOURCE_NOT_FOUND)).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("outcome")).isEqualTo("NOT_FOUND");
            assertThat(row.get("user_id")).isEqualTo(reviewerB.getId());
            assertThat(row.get("entity_type")).isEqualTo("CONTRACT");
            assertThat(row.get("entity_id")).isEqualTo(contractOfA.getId());
        });
        assertThat(rowsOf(orgB, AuditAction.RESOURCE_NOT_FOUND).get(0).get("request_id")).isEqualTo(requestId(read));
        // The owner's log shows nothing about it: the attempt belongs to the asker's organization.
        assertThat(rowsOf(orgA, AuditAction.RESOURCE_NOT_FOUND)).isEmpty();
        // The audit entry says the same for a contract that does not exist, so it reveals nothing about the owner.
        mvc.perform(get("/api/v1/contracts/" + (contractOfA.getId() + 1_000_000))
                .header(HttpHeaders.AUTHORIZATION, bearer(reviewerB))).andExpect(status().isNotFound());
        assertThat(rowsOf(orgB, AuditAction.RESOURCE_NOT_FOUND)).hasSize(3);
    }

    @Test
    void harmlessMissesDoNotFloodTheLog() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        Contract unanalyzed = data.contract(org, uploader, ContractStatus.UPLOADED);

        for (int i = 0; i < 5; i++) {
            // "No analysis yet" is an everyday 404 in the UI; an unknown URL is just a scanner.
            getAnalysis(bearer(uploader), unanalyzed.getId()).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/this-does-not-exist").header(HttpHeaders.AUTHORIZATION, bearer(uploader)))
                    .andExpect(status().isNotFound());
        }

        assertThat(rowsOf(org)).isEmpty();
    }

    // ---- nothing sensitive, ever ----

    @Test
    void noPasswordTokenContractTextTitleCommentOrAnalysisTextEverReachesTheTable() throws Exception {
        String stamp = String.valueOf(System.nanoTime());
        String wrongPassword = "WRONG-PASSWORD-MARKER-" + stamp;
        String title = "TITLE-MARKER-" + stamp;
        String text = "CONTRACT-TEXT-MARKER-" + stamp + " The vendor accepts unlimited liability for late delivery.";
        String comment = "COMMENT-MARKER-" + stamp;
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        AppUser reviewer = data.user(org, Role.REVIEWER);

        login(uploader.getUsername(), wrongPassword, CLIENT_IP);
        MvcResult good = login(uploader.getUsername(), TestData.PASSWORD, CLIENT_IP);
        String token = json.readTree(good.getResponse().getContentAsString()).get("token").asText();
        String uploaderAuth = "Bearer " + token;
        ResultActionsHolder created = createContract(uploaderAuth, title, text);
        analyze(uploaderAuth, created.contractId()).andExpect(status().isAccepted());
        awaitStatus(created.contractId(), ContractStatus.ANALYZED);
        awaitIdle();
        review(bearer(reviewer), created.contractId(),
                "{\"decision\":\"REJECTED\",\"comment\":\"" + comment + "\"}");
        mvc.perform(get("/api/v1/audit").header(HttpHeaders.AUTHORIZATION, uploaderAuth)); // refused: recorded

        List<Map<String, Object>> everything = jdbc.queryForList("SELECT * FROM audit_log");
        String dump = everything.toString();
        assertThat(everything).isNotEmpty();
        assertThat(rowsOf(org)).hasSizeGreaterThanOrEqualTo(6);
        assertThat(dump).doesNotContain(wrongPassword).doesNotContain(TestData.PASSWORD)
                .doesNotContain(token).doesNotContain("Bearer").doesNotContain("eyJ")
                .doesNotContain(title).doesNotContain("CONTRACT-TEXT-MARKER").doesNotContain(comment)
                .doesNotContain("unlimited liability").doesNotContain(uploader.getUsername());
    }

    @Test
    void theTableHasNoColumnThatCouldHoldFreeTextOrASecret() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "SELECT column_name, data_type, character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'audit_log'");

        assertThat(columns).extracting(row -> String.valueOf(row.get("COLUMN_NAME")).toLowerCase())
                .containsExactlyInAnyOrder("id", "occurred_at", "organization_id", "user_id", "action",
                        "entity_type", "entity_id", "outcome", "request_id", "ip_address");
        assertThat(columns).allSatisfy(row -> {
            assertThat(String.valueOf(row.get("DATA_TYPE")).toLowerCase()).isIn("bigint", "datetime", "varchar");
            if ("varchar".equalsIgnoreCase(String.valueOf(row.get("DATA_TYPE")))) {
                assertThat(((Number) row.get("CHARACTER_MAXIMUM_LENGTH")).intValue()).isLessThanOrEqualTo(64);
            }
        });
    }
}
