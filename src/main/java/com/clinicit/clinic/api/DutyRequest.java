package com.clinicit.clinic.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Going live (seeing patients) or offline.
 *
 * @param room where patients go when called; omitted keeps the current room, blank clears it
 */
public record DutyRequest(@NotNull Boolean live, @Size(max = 40) String room) {}
