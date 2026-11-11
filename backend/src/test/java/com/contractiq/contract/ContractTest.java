package com.contractiq.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.contractiq.common.errors.InvalidTransitionException;
import org.junit.jupiter.api.Test;

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

    @Test
    void disallowedMoveThrowsAndKeepsStatus() {
        Contract contract = newContract();
        assertThatThrownBy(() -> contract.moveTo(ContractStatus.APPROVED))
                .isInstanceOf(InvalidTransitionException.class);
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.UPLOADED);
    }
}
