package com.contractiq.common.errors;

import com.contractiq.contract.ContractStatus;

/**
 * Thrown when a contract is asked to move to a status the lifecycle does not allow.
 * The error handler turns it into a 409 Conflict.
 */
public class InvalidTransitionException extends RuntimeException {

    public InvalidTransitionException(ContractStatus from, ContractStatus to) {
        super("Cannot move contract from " + from + " to " + to);
    }
}
