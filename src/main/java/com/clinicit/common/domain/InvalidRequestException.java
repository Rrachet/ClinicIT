package com.clinicit.common.domain;

/** A request that is invalid on its face, independent of current data (HTTP 400). */
public class InvalidRequestException extends RuntimeException {

    private final String code;

    public InvalidRequestException(String message) {
        this("VALIDATION_ERROR", message);
    }

    public InvalidRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() { return code; }
}
