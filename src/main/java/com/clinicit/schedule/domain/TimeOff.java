package com.clinicit.schedule.domain;

import java.time.LocalDateTime;
import java.util.UUID;

/** Leave or another period when the doctor sees no one. Clinic-local, end exclusive. */
public record TimeOff(UUID id, UUID doctorId, LocalDateTime startsAt, LocalDateTime endsAt, String reason) {

    public boolean overlaps(LocalDateTime from, LocalDateTime to) {
        return startsAt.isBefore(to) && from.isBefore(endsAt);
    }

    public boolean covers(LocalDateTime at) {
        return !at.isBefore(startsAt) && at.isBefore(endsAt);
    }
}
