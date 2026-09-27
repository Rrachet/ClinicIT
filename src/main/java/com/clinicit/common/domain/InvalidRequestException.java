package com.clinicit.common.domain;

/** A request that is invalid on its face, independent of current data (HTTP 400). */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
