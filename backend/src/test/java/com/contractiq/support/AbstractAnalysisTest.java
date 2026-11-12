package com.contractiq.support;

import static org.awaitility.Awaitility.await;

import com.contractiq.contract.Contract;
import com.contractiq.contract.ContractRepository;
import com.contractiq.contract.ContractStatus;
import com.contractiq.contract.ai.AnalysisResult;
import com.contractiq.contract.ai.AnalysisResult.RiskFlag;
import com.contractiq.contract.ai.AnalysisResult.Severity;
import com.contractiq.contract.ai.ContractAnalysisRepository;
import com.contractiq.organization.Organization;
import com.contractiq.user.AppUser;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base class for analysis tests: HTTP tests with a scriptable analyzer, plus helpers to create a
 * contract with a unique marker, call the endpoints and wait for the background job.
 */
@Import(ControllableAnalyzerConfig.class)
public abstract class AbstractAnalysisTest extends AbstractMockMvcTest {

    @Autowired
    protected ControllableAnalyzer analyzer;

    @Autowired
    protected ContractRepository contracts;

    @Autowired
    protected ContractAnalysisRepository analyses;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    @Qualifier("analysisExecutor")
    private ThreadPoolTaskExecutor analysisExecutor;

    /** Waits until no analysis job is running or queued, so a test can safely check final state. */
    protected void awaitIdle() {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(25)).until(() ->
                analysisExecutor.getActiveCount() == 0
                        && analysisExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }

    /** A unique lowercase word with no digits, safe to put in a contract (redaction leaves it alone). */
    protected static String marker() {
        StringBuilder builder = new StringBuilder("mk");
        for (int i = 0; i < 14; i++) {
            builder.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        }
        return builder.toString();
    }

    protected Contract contractWithText(Organization org, AppUser creator, ContractStatus status, String text) {
        return data.contract(org, creator, status, text);
    }

    protected ResultActions analyze(String authorization, Long contractId) throws Exception {
        return mvc.perform(post("/api/v1/contracts/" + contractId + "/analyze")
                .header(HttpHeaders.AUTHORIZATION, authorization));
    }

    protected ResultActions getAnalysis(String authorization, Long contractId) throws Exception {
        return mvc.perform(get("/api/v1/contracts/" + contractId + "/analysis")
                .header(HttpHeaders.AUTHORIZATION, authorization));
    }

    protected ContractStatus statusOf(Long contractId) {
        return contracts.findById(contractId).orElseThrow().getStatus();
    }

    protected void awaitStatus(Long contractId, ContractStatus expected) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(25))
                .until(() -> statusOf(contractId) == expected);
    }

    /** A valid result with one risk flag carrying the given quote. */
    protected static AnalysisResult resultWithQuote(String quote) {
        return new AnalysisResult(List.of("Party A", "Party B"), null, null, null, null, List.of(),
                List.of(new RiskFlag("Clause", Severity.LOW, "Because.", quote)), "A summary.");
    }

    /** A result that is invalid because its quote is not in any contract text. */
    protected static AnalysisResult invalidResult() {
        return resultWithQuote("this quote is not in the contract text at all");
    }
}
