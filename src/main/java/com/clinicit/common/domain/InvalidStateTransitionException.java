package com.clinicit.common.domain;

public class InvalidStateTransitionException extends BusinessRuleException {

    public InvalidStateTransitionException(String entity, Enum<?> from, Enum<?> to) {
        super("INVALID_STATE", "Cannot transition " + entity + " from " + from + " to " + to);
    }
}
