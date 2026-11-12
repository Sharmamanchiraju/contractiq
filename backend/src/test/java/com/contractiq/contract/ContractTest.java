package com.contractiq.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.contractiq.common.errors.ConflictException;
import com.contractiq.common.errors.InvalidTransitionException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Checks that {@link Contract#moveTo(ContractStatus)} applies the lifecycle rules.
 */
class ContractTest {

    private Contract newContract() {
        return new Contract(1L, "Title", "Vendor", "Text", 1L);
    }

    @Test
    void newContractStartsAsUploaded() {
        assertThat(newContract().getStatus()).isEqualTo(ContractStatus.UPLOADED);
    }

    @Test
    void allowedMoveChangesStatus() {
        Contract contract = newContract();
        contract.moveTo(ContractStatus.ANALYZING);
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.ANALYZING);
    }

    // ---- analysis state moves ----

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Duration STALE_AFTER = Duration.ofMinutes(10);

    private Contract contractIn(ContractStatus status, Instant updatedAt) {
        Contract contract = newContract();
        ReflectionTestUtils.setField(contract, "status", status);
        ReflectionTestUtils.setField(contract, "updatedAt", updatedAt);
        return contract;
    }

    @ParameterizedTest
    @EnumSource(value = ContractStatus.class, names = {"UPLOADED", "ANALYZED", "NEEDS_MANUAL_REVIEW"})
    void analysisCanStartFromUploadedAnalyzedAndNeedsManualReview(ContractStatus from) {
        Contract contract = contractIn(from, NOW);
        ReflectionTestUtils.setField(contract, "analysisErrorCode", "ANALYSIS_TIMEOUT");

        contract.beginAnalysis(NOW, STALE_AFTER);

        assertThat(contract.getStatus()).isEqualTo(ContractStatus.ANALYZING);
        assertThat(contract.getAnalysisErrorCode()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = ContractStatus.class, names = {"APPROVED", "REJECTED"})
    void analysisCannotStartOnADecidedContract(ContractStatus from) {
        Contract contract = contractIn(from, NOW);

        assertThatThrownBy(() -> contract.beginAnalysis(NOW, STALE_AFTER))
                .isInstanceOf(InvalidTransitionException.class);
        assertThat(contract.getStatus()).isEqualTo(from);
    }

    @Test
    void analysisCannotStartWhileAFreshAnalysisIsRunning() {
        Contract contract = contractIn(ContractStatus.ANALYZING, NOW.minus(Duration.ofMinutes(9)));

        assertThatThrownBy(() -> contract.beginAnalysis(NOW, STALE_AFTER)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aStaleAnalyzingContractCanBeRestarted() {
        Contract contract = contractIn(ContractStatus.ANALYZING, NOW.minus(Duration.ofMinutes(11)));

        contract.beginAnalysis(NOW, STALE_AFTER);

        assertThat(contract.getStatus()).isEqualTo(ContractStatus.ANALYZING);
        assertThat(contract.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void completingAnalysisMovesAnalyzingToAnalyzedNeverFurther() {
        Contract contract = contractIn(ContractStatus.ANALYZING, NOW);

        contract.completeAnalysis();

        assertThat(contract.getStatus()).isEqualTo(ContractStatus.ANALYZED);
    }

    @Test
    void failingAnalysisMovesToNeedsManualReviewWithTheSafeCode() {
        Contract contract = contractIn(ContractStatus.ANALYZING, NOW);

        contract.failAnalysis("ANALYSIS_PROVIDER_ERROR");

        assertThat(contract.getStatus()).isEqualTo(ContractStatus.NEEDS_MANUAL_REVIEW);
        assertThat(contract.getAnalysisErrorCode()).isEqualTo("ANALYSIS_PROVIDER_ERROR");
    }

    @ParameterizedTest
    @EnumSource(value = ContractStatus.class, names = {"UPLOADED", "ANALYZED", "NEEDS_MANUAL_REVIEW", "APPROVED", "REJECTED"})
    void completeAndFailOnlyWorkFromAnalyzing(ContractStatus from) {
        assertThatThrownBy(() -> contractIn(from, NOW).completeAnalysis())
                .isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> contractIn(from, NOW).failAnalysis("ANALYSIS_TIMEOUT"))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void disallowedMoveThrowsAndKeepsStatus() {
        Contract contract = newContract();
        assertThatThrownBy(() -> contract.moveTo(ContractStatus.APPROVED))
                .isInstanceOf(InvalidTransitionException.class);
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.UPLOADED);
    }
}
