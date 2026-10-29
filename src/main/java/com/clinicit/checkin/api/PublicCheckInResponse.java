package com.clinicit.checkin.api;

import java.util.List;
import java.util.UUID;

/**
 * The check-in page for a clinic: which visit types can be seen right now.
 * No doctor or patient details.
 *
 * @param open at least one doctor is live, so some visit type can be seen
 */
public record PublicCheckInResponse(String clinicName, boolean open, List<Option> visitTypes) {

    /** @param available a live doctor sees this visit type now */
    public record Option(UUID id, String name, String description, boolean available) {}
}
