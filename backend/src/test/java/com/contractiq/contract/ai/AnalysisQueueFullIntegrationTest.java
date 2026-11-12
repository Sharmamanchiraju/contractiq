package com.contractiq.contract.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.contractiq.contract.Contract;
import com.contractiq.contract.ContractStatus;
import com.contractiq.organization.Organization;
import com.contractiq.support.AbstractAnalysisTest;
import com.contractiq.support.ControllableAnalyzer.Script;
import com.contractiq.user.AppUser;
import com.contractiq.user.Role;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves the job pool is bounded: with one thread and a queue of one, a third analysis is refused
 * and ends as NEEDS_MANUAL_REVIEW with the safe code ANALYSIS_UNAVAILABLE, instead of work piling up.
 */
@TestPropertySource(properties = {
        "app.ai.executor.core-size=1",
        "app.ai.executor.max-size=1",
        "app.ai.executor.queue-capacity=1"})
class AnalysisQueueFullIntegrationTest extends AbstractAnalysisTest {

    @Test
    void aFullQueueGivesAnUnavailableFailureNotUnboundedWork() throws Exception {
        Organization org = data.organization();
        AppUser uploader = data.user(org, Role.UPLOADER);
        String auth = bearer(uploader);
        String markerA = marker();
        String markerB = marker();
        String markerC = marker();
        Contract running = contractWithText(org, uploader, ContractStatus.UPLOADED, markerA + ". Plain text.");
        Contract queued = contractWithText(org, uploader, ContractStatus.UPLOADED, markerB + ". Plain text.");
        Contract refused = contractWithText(org, uploader, ContractStatus.UPLOADED, markerC + ". Plain text.");
        CountDownLatch release = new CountDownLatch(1);
        Script scriptA = analyzer.script(markerA).thenBlockUntil(release);
        analyzer.script(markerB);
        Script scriptC = analyzer.script(markerC);

        analyze(auth, running.getId()).andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(15)).until(() -> scriptA.calls().size() == 1);
        analyze(auth, queued.getId()).andExpect(status().isAccepted());
        analyze(auth, refused.getId()).andExpect(status().isAccepted());

        awaitStatus(refused.getId(), ContractStatus.NEEDS_MANUAL_REVIEW);
        assertThat(contracts.findById(refused.getId()).orElseThrow().getAnalysisErrorCode())
                .isEqualTo("ANALYSIS_UNAVAILABLE");
        assertThat(scriptC.calls()).isEmpty();

        // The two accepted jobs still finish normally once the first one is released.
        release.countDown();
        awaitStatus(running.getId(), ContractStatus.ANALYZED);
        awaitStatus(queued.getId(), ContractStatus.ANALYZED);
        // And the refused contract can be analyzed again now that there is room.
        analyze(auth, refused.getId()).andExpect(status().isAccepted());
        awaitStatus(refused.getId(), ContractStatus.ANALYZED);
        assertThat(contracts.findById(refused.getId()).orElseThrow().getAnalysisErrorCode()).isNull();
    }
}
