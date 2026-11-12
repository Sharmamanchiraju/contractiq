package com.contractiq.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contractiq.organization.Organization;
import com.contractiq.support.AbstractMockMvcTest;
import com.contractiq.user.AppUser;
import com.contractiq.user.Role;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Proves that a concurrent change to a contract produces a 409 (optimistic locking) and that no
 * Review row is saved when the status change fails, i.e. both happen in one transaction.
 */
class ReviewConcurrencyIntegrationTest extends AbstractMockMvcTest {

    @MockitoSpyBean
    private ContractRepository contracts;

    @Autowired
    private ReviewRepository reviews;

    @Test
    void reviewBasedOnAStaleVersionGives409AndSavesNoReview() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        AppUser reviewer = data.user(org, Role.REVIEWER);
        Contract created = data.contract(org, uploader, ContractStatus.ANALYZED);

        // Someone else reads the contract...
        Contract stale = contracts.findById(created.getId()).orElseThrow();
        // ...and another writer changes it first, which bumps the version.
        Contract fresh = contracts.findById(created.getId()).orElseThrow();
        fresh.moveTo(ContractStatus.NEEDS_MANUAL_REVIEW);
        contracts.saveAndFlush(fresh);
        assertThat(contracts.findById(created.getId()).orElseThrow().getVersion()).isGreaterThan(stale.getVersion());

        // The reviewer's request now works on the stale copy, exactly as in a real race.
        doReturn(Optional.of(stale)).when(contracts).findByIdAndOrganizationId(created.getId(), org.getId());

        mvc.perform(post("/api/v1/contracts/" + created.getId() + "/review")
                        .header(HttpHeaders.AUTHORIZATION, bearer(reviewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("decision", "APPROVED"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // The other writer's change stands, and the failed review left no trace.
        Contract after = contracts.findById(created.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ContractStatus.NEEDS_MANUAL_REVIEW);
        assertThat(reviews.findByContractIdOrderByCreatedAtAsc(created.getId())).isEmpty();
    }

    @Test
    void twoSimultaneousReviewsLeaveExactlyOneReviewRow() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        Contract contract = data.contract(org, uploader, ContractStatus.ANALYZED);
        String reviewerA = bearer(data.user(org, Role.REVIEWER));
        String reviewerB = bearer(data.user(org, Role.REVIEWER));

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String auth : List.of(reviewerA, reviewerB)) {
                Callable<Integer> call = () -> {
                    go.await();
                    return mvc.perform(post("/api/v1/contracts/" + contract.getId() + "/review")
                                    .header(HttpHeaders.AUTHORIZATION, auth)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"decision\":\"APPROVED\"}"))
                            .andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(reviews.findByContractIdOrderByCreatedAtAsc(contract.getId())).hasSize(1);
        assertThat(contracts.findById(contract.getId()).orElseThrow().getStatus()).isEqualTo(ContractStatus.APPROVED);
    }
}
