package com.clinicit.checkin.api;

import java.util.List;
import java.util.UUID;

public record VisitTypeResponse(
        UUID id,
        String name,
        String description,
        boolean anyDoctor,
        List<UUID> doctorIds,
        int sortOrder,
        boolean active
) {}
