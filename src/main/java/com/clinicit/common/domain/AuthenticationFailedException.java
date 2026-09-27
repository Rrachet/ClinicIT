package com.clinicit.common.domain;

/** Login failed. Deliberately says nothing about which part was wrong (HTTP 401). */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException() {
        super("Invalid email or password");
    }
}
