package com.clinicit.checkin.api;

/**
 * Where the patient was queued. statusCode opens their status page (/status/{code}).
 *
 * @param alreadyCheckedIn they were already in today's queue; this is that same place
 */
public record SelfCheckInResponse(
        String statusCode,
        int tokenNumber,
        String doctorName,
        String room,
        boolean alreadyCheckedIn
) {}
