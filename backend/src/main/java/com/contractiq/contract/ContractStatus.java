package com.contractiq.contract;

import java.util.EnumSet;
import java.util.Set;

/**
 * The lifecycle of a contract. The allowed moves live here, in one place, so no other class
 * needs to know the rules; {@link #canMoveTo(ContractStatus)} is the only check callers use.
 */
public enum ContractStatus {
    UPLOADED,
    ANALYZING,
    ANALYZED,
    NEEDS_MANUAL_REVIEW,
    APPROVED,
    REJECTED;

    /**
     * Returns true if a contract in this status may move to {@code target}.
     * A move to the same status is not allowed.
     */
    public boolean canMoveTo(ContractStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<ContractStatus> allowedTargets() {
        return switch (this) {
            case UPLOADED -> EnumSet.of(ANALYZING);
            case ANALYZING -> EnumSet.of(ANALYZED, NEEDS_MANUAL_REVIEW);
            case ANALYZED -> EnumSet.of(APPROVED, REJECTED, NEEDS_MANUAL_REVIEW, ANALYZING);
            case NEEDS_MANUAL_REVIEW -> EnumSet.of(APPROVED, REJECTED, ANALYZING);
            case APPROVED, REJECTED -> EnumSet.noneOf(ContractStatus.class);
        };
    }
}
