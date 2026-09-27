package com.clinicit.common.domain;

/**
 * A request that is well-formed but not allowed by the current state of the
 * domain (e.g. calling the next patient while one is still in consultation).
 * The code is stable and machine-readable so clients can react to it.
 */
public class BusinessRuleException extends RuntimeException {

    private final String code;

    public BusinessRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
