package com.clinicit.checkin.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * @param anyDoctor true: every live doctor can see this visit type (e.g. "General check-up")
 * @param doctorIds otherwise, the doctors who see it
 * @param active    false hides it from the check-in page
 */
public record VisitTypeRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 200) String description,
        boolean anyDoctor,
        @NotNull @Size(max = 100) List<@NotNull UUID> doctorIds,
        @Min(0) @Max(1000) Integer sortOrder,
        Boolean active
) {}
