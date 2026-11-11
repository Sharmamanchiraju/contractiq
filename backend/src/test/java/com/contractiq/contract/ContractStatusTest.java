package com.contractiq.contract;

import static com.contractiq.contract.ContractStatus.ANALYZED;
import static com.contractiq.contract.ContractStatus.ANALYZING;
import static com.contractiq.contract.ContractStatus.APPROVED;
import static com.contractiq.contract.ContractStatus.NEEDS_MANUAL_REVIEW;
import static com.contractiq.contract.ContractStatus.REJECTED;
import static com.contractiq.contract.ContractStatus.UPLOADED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Checks the lifecycle rules for every one of the 36 (from, to) pairs against an independently
 * written table, so a change to the enum's rules must be matched by a change here.
 */
class ContractStatusTest {

    private static final Map<ContractStatus, Set<ContractStatus>> EXPECTED = new EnumMap<>(ContractStatus.class);

    static {
        EXPECTED.put(UPLOADED, EnumSet.of(ANALYZING));
        EXPECTED.put(ANALYZING, EnumSet.of(ANALYZED, NEEDS_MANUAL_REVIEW));
        EXPECTED.put(ANALYZED, EnumSet.of(APPROVED, REJECTED, NEEDS_MANUAL_REVIEW, ANALYZING));
        EXPECTED.put(NEEDS_MANUAL_REVIEW, EnumSet.of(APPROVED, REJECTED, ANALYZING));
        EXPECTED.put(APPROVED, EnumSet.noneOf(ContractStatus.class));
        EXPECTED.put(REJECTED, EnumSet.noneOf(ContractStatus.class));
    }

    @TestFactory
    Stream<DynamicTest> everyPairMatchesTheExpectedTable() {
        return Stream.of(ContractStatus.values()).flatMap(from ->
                Stream.of(ContractStatus.values()).map(to -> {
                    boolean allowed = EXPECTED.get(from).contains(to);
                    return dynamicTest(from + " -> " + to + (allowed ? " is allowed" : " is rejected"),
                            () -> assertThat(from.canMoveTo(to)).isEqualTo(allowed));
                }));
    }

    @Test
    void reanalysisMovesAreAllowed() {
        assertThat(ANALYZED.canMoveTo(ANALYZING)).isTrue();
        assertThat(NEEDS_MANUAL_REVIEW.canMoveTo(ANALYZING)).isTrue();
    }

    @Test
    void reviewMovesAreOnlyAllowedFromAnalyzedOrNeedsManualReview() {
        for (ContractStatus from : ContractStatus.values()) {
            boolean expected = from == ANALYZED || from == NEEDS_MANUAL_REVIEW;
            assertThat(from.canMoveTo(APPROVED)).as(from + " -> APPROVED").isEqualTo(expected);
            assertThat(from.canMoveTo(REJECTED)).as(from + " -> REJECTED").isEqualTo(expected);
        }
    }

    @Test
    void approvedAndRejectedAreTerminal() {
        for (ContractStatus target : ContractStatus.values()) {
            assertThat(APPROVED.canMoveTo(target)).isFalse();
            assertThat(REJECTED.canMoveTo(target)).isFalse();
        }
    }

    @Test
    void noStatusMovesToItself() {
        for (ContractStatus status : ContractStatus.values()) {
            assertThat(status.canMoveTo(status)).isFalse();
        }
    }
}
