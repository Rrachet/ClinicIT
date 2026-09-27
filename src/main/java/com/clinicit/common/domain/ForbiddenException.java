package com.clinicit.common.domain;

/** Authenticated, in the right clinic, but not allowed to do this (HTTP 403). */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
