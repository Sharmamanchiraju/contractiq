package com.contractiq.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contractiq.organization.Organization;
import com.contractiq.support.AbstractMockMvcTest;
import com.contractiq.user.AppUser;
import com.contractiq.user.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * End-to-end tests of the contract endpoints through the full security chain and a real MySQL:
 * create, list (paging, filter, no contract text), detail, tenant isolation, review rules and
 * the full lifecycle.
 */
class ContractApiIntegrationTest extends AbstractMockMvcTest {

    @Autowired
    private ContractRepository contracts;

    @Autowired
    private ReviewRepository reviews;

    private ResultActions createContract(String authorization, Object body) throws Exception {
        return mvc.perform(post("/api/v1/contracts")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private ResultActions review(String authorization, Long contractId, Object body) throws Exception {
        return mvc.perform(post("/api/v1/contracts/" + contractId + "/review")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private static Map<String, Object> validContract() {
        return Map.of("title", "Office supplies", "vendorName", "Acme Ltd", "contractText", "The vendor shall deliver...");
    }

    // ---- authentication ----

    @Test
    void everyContractEndpointRequiresAToken() throws Exception {
        mvc.perform(get("/api/v1/contracts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/contracts/1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/contracts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/contracts/1/review").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- create ----

    @Test
    void anyRoleCanCreateAndTheContractStartsAsUploaded() throws Exception {
        Organization org = data.organization();
        for (Role role : Role.values()) {
            AppUser user = data.user(org, role);
            createContract(bearer(user), validContract())
                    .andExpect(status().isCreated())
                    .andExpect(header().string(HttpHeaders.LOCATION, startsWith("/api/v1/contracts/")))
                    .andExpect(jsonPath("$.status").value("UPLOADED"))
                    .andExpect(jsonPath("$.createdBy").value(user.getId()))
                    .andExpect(jsonPath("$.contractText").doesNotExist());
        }
    }

    @Test
    void organizationCreatorAndStatusComeFromTheTokenNotTheBody() throws Exception {
        Organization org = data.organization();
        Organization other = data.organization();
        AppUser user = data.user(org, Role.UPLOADER);
        Map<String, Object> body = Map.of("title", "T", "vendorName", "V", "contractText", "text",
                "organizationId", other.getId(), "createdBy", 999999, "status", "APPROVED");

        MvcResult result = createContract(bearer(user), body).andExpect(status().isCreated()).andReturn();

        Long id = json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        Contract saved = contracts.findById(id).orElseThrow();
        assertThat(saved.getOrganizationId()).isEqualTo(org.getId());
        assertThat(saved.getCreatedBy()).isEqualTo(user.getId());
        assertThat(saved.getStatus()).isEqualTo(ContractStatus.UPLOADED);
    }

    @Test
    void createValidatesEveryField() throws Exception {
        String auth = bearer(data.user(data.organization(), Role.UPLOADER));

        createContract(auth, Map.of("title", "", "vendorName", "V", "contractText", "t"))
                .andExpect(status().isBadRequest());
        createContract(auth, Map.of("title", "T", "vendorName", " ", "contractText", "t"))
                .andExpect(status().isBadRequest());
        createContract(auth, Map.of("title", "T", "vendorName", "V", "contractText", ""))
                .andExpect(status().isBadRequest());
        createContract(auth, Map.of("title", "x".repeat(301), "vendorName", "V", "contractText", "t"))
                .andExpect(status().isBadRequest());
        createContract(auth, Map.of("vendorName", "V", "contractText", "t"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void contractTextAtTheLimitIsAcceptedAndOneOverIsRejected() throws Exception {
        String auth = bearer(data.user(data.organization(), Role.UPLOADER));

        createContract(auth, Map.of("title", "T", "vendorName", "V", "contractText", "a".repeat(200_000)))
                .andExpect(status().isCreated());
        createContract(auth, Map.of("title", "T", "vendorName", "V", "contractText", "a".repeat(200_001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("contractText"));
    }

    // ---- detail and tenant isolation ----

    @Test
    void timestampsInTheCreateResponseMatchWhatIsStored() throws Exception {
        AppUser user = data.user(data.organization(), Role.UPLOADER);

        MvcResult created = createContract(bearer(user), validContract()).andExpect(status().isCreated()).andReturn();
        var createdBody = json.readTree(created.getResponse().getContentAsString());
        MvcResult fetched = mvc.perform(get("/api/v1/contracts/" + createdBody.get("id").asLong())
                .header(HttpHeaders.AUTHORIZATION, bearer(user))).andReturn();
        var fetchedBody = json.readTree(fetched.getResponse().getContentAsString());

        assertThat(createdBody.get("createdAt")).isEqualTo(fetchedBody.get("createdAt"));
        assertThat(createdBody.get("updatedAt")).isEqualTo(fetchedBody.get("updatedAt"));
    }

    @Test
    void detailIncludesTheContractText() throws Exception {
        Organization org = data.organization();
        AppUser user = data.user(org, Role.UPLOADER);
        Contract contract = data.contract(org, user, ContractStatus.UPLOADED);

        mvc.perform(get("/api/v1/contracts/" + contract.getId()).header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(contract.getId()))
                .andExpect(jsonPath("$.contractText").value("Contract text for tests."));
    }

    @Test
    void anotherOrganizationGets404AndCannotTellTheContractExists() throws Exception {
        Organization orgA = data.organization();
        Organization orgB = data.organization();
        AppUser uploaderA = data.user(orgA, Role.UPLOADER);
        AppUser uploaderB = data.user(orgB, Role.UPLOADER);
        Contract contractOfA = data.contract(orgA, uploaderA, ContractStatus.UPLOADED);

        MvcResult crossTenant = mvc.perform(get("/api/v1/contracts/" + contractOfA.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(uploaderB)))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult missing = mvc.perform(get("/api/v1/contracts/" + (contractOfA.getId() + 1_000_000))
                        .header(HttpHeaders.AUTHORIZATION, bearer(uploaderB)))
                .andExpect(status().isNotFound()).andReturn();

        var a = json.readTree(crossTenant.getResponse().getContentAsString());
        var b = json.readTree(missing.getResponse().getContentAsString());
        assertThat(a.get("title")).isEqualTo(b.get("title"));
        assertThat(a.get("detail")).isEqualTo(b.get("detail"));
        assertThat(crossTenant.getResponse().getContentAsString()).doesNotContain("Contract text for tests.");
        // The owner still sees it.
        mvc.perform(get("/api/v1/contracts/" + contractOfA.getId()).header(HttpHeaders.AUTHORIZATION, bearer(uploaderA)))
                .andExpect(status().isOk());
    }

    @Test
    void anotherOrganizationCannotReviewAndTheContractIsUntouched() throws Exception {
        Organization orgA = data.organization();
        Organization orgB = data.organization();
        AppUser uploaderA = data.user(orgA, Role.UPLOADER);
        AppUser reviewerB = data.user(orgB, Role.REVIEWER);
        Contract contractOfA = data.contract(orgA, uploaderA, ContractStatus.ANALYZED);

        review(bearer(reviewerB), contractOfA.getId(), Map.of("decision", "APPROVED"))
                .andExpect(status().isNotFound());

        assertThat(contracts.findById(contractOfA.getId()).orElseThrow().getStatus())
                .isEqualTo(ContractStatus.ANALYZED);
        assertThat(reviews.findByContractIdOrderByCreatedAtAsc(contractOfA.getId())).isEmpty();
    }

    @Test
    void listShowsOnlyTheCallersOrganization() throws Exception {
        Organization orgA = data.organization();
        Organization orgB = data.organization();
        AppUser uploaderA = data.user(orgA, Role.UPLOADER);
        AppUser uploaderB = data.user(orgB, Role.UPLOADER);
        data.contract(orgA, uploaderA, ContractStatus.UPLOADED);
        data.contract(orgA, uploaderA, ContractStatus.UPLOADED);
        data.contract(orgB, uploaderB, ContractStatus.UPLOADED);

        mvc.perform(get("/api/v1/contracts").header(HttpHeaders.AUTHORIZATION, bearer(uploaderA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/contracts").header(HttpHeaders.AUTHORIZATION, bearer(uploaderB)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ---- list: paging, sorting, filter, no contract text ----

    @Test
    void listIsPagedNewestFirstAndNeverContainsContractText() throws Exception {
        Organization org = data.organization();
        AppUser user = data.user(org, Role.UPLOADER);
        List<Long> idsOldestFirst = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            idsOldestFirst.add(data.contract(org, user, ContractStatus.UPLOADED).getId());
        }
        String auth = bearer(user);

        // Default page size is 20, newest first.
        mvc.perform(get("/api/v1/contracts").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].id").value(idsOldestFirst.get(24)))
                .andExpect(jsonPath("$.items[19].id").value(idsOldestFirst.get(5)))
                .andExpect(jsonPath("$.items[0].contractText").doesNotExist())
                .andExpect(jsonPath("$.items[*].contractText").isEmpty());

        // Second page holds the 5 oldest.
        mvc.perform(get("/api/v1/contracts?page=1").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.items[4].id").value(idsOldestFirst.get(0)));

        // A custom size works, and a size above 100 is capped at 100.
        mvc.perform(get("/api/v1/contracts?size=10").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.items", hasSize(10)))
                .andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get("/api/v1/contracts?size=500").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.items", hasSize(25)));
    }

    @Test
    void invalidPagingParametersGive400() throws Exception {
        String auth = bearer(data.user(data.organization(), Role.UPLOADER));

        mvc.perform(get("/api/v1/contracts?size=0").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/contracts?page=-1").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/contracts?size=abc").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/contracts?status=BOGUS").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void clientCannotChooseTheSortOrder() throws Exception {
        Organization org = data.organization();
        AppUser user = data.user(org, Role.UPLOADER);
        Long first = data.contract(org, user, ContractStatus.UPLOADED).getId();
        Long second = data.contract(org, user, ContractStatus.UPLOADED).getId();

        mvc.perform(get("/api/v1/contracts?sort=createdAt,asc").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(second))
                .andExpect(jsonPath("$.items[1].id").value(first));
    }

    @Test
    void statusFilterReturnsOnlyMatchingContracts() throws Exception {
        Organization org = data.organization();
        AppUser user = data.user(org, Role.UPLOADER);
        for (int i = 0; i < 3; i++) {
            data.contract(org, user, ContractStatus.ANALYZED);
        }
        for (int i = 0; i < 2; i++) {
            data.contract(org, user, ContractStatus.NEEDS_MANUAL_REVIEW);
        }
        data.contract(org, user, ContractStatus.UPLOADED);
        String auth = bearer(user);

        mvc.perform(get("/api/v1/contracts?status=ANALYZED").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[*].status", everyItem(is("ANALYZED"))));
        mvc.perform(get("/api/v1/contracts?status=NEEDS_MANUAL_REVIEW").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/contracts?status=APPROVED").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/contracts").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$.totalElements").value(6));
    }

    // ---- review ----

    @Test
    void uploaderCannotReview() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        Contract contract = data.contract(org, uploader, ContractStatus.ANALYZED);

        review(bearer(uploader), contract.getId(), Map.of("decision", "APPROVED"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        assertThat(contracts.findById(contract.getId()).orElseThrow().getStatus()).isEqualTo(ContractStatus.ANALYZED);
        assertThat(reviews.findByContractIdOrderByCreatedAtAsc(contract.getId())).isEmpty();
    }

    @Test
    void reviewerAndAdminCanApproveAnalyzedAndNeedsManualReview() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        for (Role role : List.of(Role.REVIEWER, Role.ADMIN)) {
            for (ContractStatus from : List.of(ContractStatus.ANALYZED, ContractStatus.NEEDS_MANUAL_REVIEW)) {
                AppUser decider = data.user(org, role);
                Contract contract = data.contract(org, uploader, from);

                review(bearer(decider), contract.getId(), Map.of("decision", "APPROVED"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.decision").value("APPROVED"))
                        .andExpect(jsonPath("$.contractStatus").value("APPROVED"))
                        .andExpect(jsonPath("$.reviewerId").value(decider.getId()));
            }
        }
    }

    @Test
    void reviewerIdComesFromTheTokenNotTheBody() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        AppUser reviewer = data.user(org, Role.REVIEWER);
        Contract contract = data.contract(org, uploader, ContractStatus.ANALYZED);

        review(bearer(reviewer), contract.getId(),
                Map.of("decision", "APPROVED", "reviewerId", uploader.getId(), "contractId", 999999))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewerId").value(reviewer.getId()))
                .andExpect(jsonPath("$.contractId").value(contract.getId()));

        List<Review> saved = reviews.findByContractIdOrderByCreatedAtAsc(contract.getId());
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getReviewerId()).isEqualTo(reviewer.getId());
    }

    @Test
    void rejectingRequiresANonBlankComment() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        String auth = bearer(data.user(org, Role.REVIEWER));
        Contract contract = data.contract(org, uploader, ContractStatus.ANALYZED);

        review(auth, contract.getId(), Map.of("decision", "REJECTED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("comment"));
        review(auth, contract.getId(), Map.of("decision", "REJECTED", "comment", "   "))
                .andExpect(status().isBadRequest());
        assertThat(contracts.findById(contract.getId()).orElseThrow().getStatus()).isEqualTo(ContractStatus.ANALYZED);

        review(auth, contract.getId(), Map.of("decision", "REJECTED", "comment", "Unlimited liability clause."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractStatus").value("REJECTED"))
                .andExpect(jsonPath("$.comment").value("Unlimited liability clause."));
    }

    @Test
    void approvingNeedsNoCommentButTheCommentLengthIsLimited() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        String auth = bearer(data.user(org, Role.REVIEWER));
        Contract first = data.contract(org, uploader, ContractStatus.ANALYZED);
        Contract second = data.contract(org, uploader, ContractStatus.ANALYZED);

        review(auth, first.getId(), Map.of("decision", "APPROVED", "comment", "x".repeat(2001)))
                .andExpect(status().isBadRequest());
        review(auth, first.getId(), Map.of("decision", "APPROVED", "comment", "x".repeat(2000)))
                .andExpect(status().isOk());
        review(auth, second.getId(), Map.of("decision", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comment").doesNotExist());
    }

    @Test
    void invalidDecisionAndMissingDecisionGive400() throws Exception {
        Organization org = data.organization();
        String auth = bearer(data.user(org, Role.REVIEWER));
        Contract contract = data.contract(org, data.user(org, Role.UPLOADER), ContractStatus.ANALYZED);

        review(auth, contract.getId(), Map.of("decision", "MAYBE")).andExpect(status().isBadRequest());
        review(auth, contract.getId(), Map.of("comment", "no decision")).andExpect(status().isBadRequest());
    }

    @Test
    void reviewIsRejectedWith409UnlessStatusIsAnalyzedOrNeedsManualReview() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        String auth = bearer(data.user(org, Role.REVIEWER));

        for (ContractStatus status : List.of(ContractStatus.UPLOADED, ContractStatus.ANALYZING,
                ContractStatus.APPROVED, ContractStatus.REJECTED)) {
            Contract contract = data.contract(org, uploader, status);

            review(auth, contract.getId(), Map.of("decision", "APPROVED"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
            assertThat(contracts.findById(contract.getId()).orElseThrow().getStatus()).isEqualTo(status);
            assertThat(reviews.findByContractIdOrderByCreatedAtAsc(contract.getId())).isEmpty();
        }
    }

    @Test
    void reviewingTwiceGives409TheSecondTime() throws Exception {
        Organization org = data.organization();
        Contract contract = data.contract(org, data.user(org, Role.UPLOADER), ContractStatus.ANALYZED);
        String auth = bearer(data.user(org, Role.REVIEWER));

        review(auth, contract.getId(), Map.of("decision", "APPROVED")).andExpect(status().isOk());
        review(auth, contract.getId(), Map.of("decision", "REJECTED", "comment", "changed my mind"))
                .andExpect(status().isConflict());

        assertThat(reviews.findByContractIdOrderByCreatedAtAsc(contract.getId())).hasSize(1);
    }

    @Test
    void reviewOfAnUnknownContractIs404() throws Exception {
        String auth = bearer(data.user(data.organization(), Role.REVIEWER));

        review(auth, 987_654_321L, Map.of("decision", "APPROVED")).andExpect(status().isNotFound());
    }

    // ---- lifecycle ----

    @Test
    void fullLifecycleUploadedToAnalyzedToApproved() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        AppUser reviewer = data.user(org, Role.REVIEWER);

        MvcResult created = createContract(bearer(uploader), validContract())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andReturn();
        Long id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        // The AI analysis does not exist yet (a later phase). Until then a test moves the contract.
        review(bearer(reviewer), id, Map.of("decision", "APPROVED")).andExpect(status().isConflict());
        Contract contract = contracts.findById(id).orElseThrow();
        contract.moveTo(ContractStatus.ANALYZING);
        contract.moveTo(ContractStatus.ANALYZED);
        contracts.save(contract);

        review(bearer(reviewer), id, Map.of("decision", "APPROVED", "comment", "Looks fine."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractStatus").value("APPROVED"));

        mvc.perform(get("/api/v1/contracts/" + id).header(HttpHeaders.AUTHORIZATION, bearer(uploader)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(get("/api/v1/contracts?status=APPROVED").header(HttpHeaders.AUTHORIZATION, bearer(uploader)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }
}
