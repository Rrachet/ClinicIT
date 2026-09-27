package com.clinicit.queue.domain;

import java.security.SecureRandom;
import java.util.Base64;

/** 128-bit random, URL-safe codes (22 characters) for public queue status links. */
final class StatusCodes {

    private static final SecureRandom RANDOM = new SecureRandom();

    private StatusCodes() {}

    static String next() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
