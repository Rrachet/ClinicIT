package com.clinicit.clinic.api;

import java.time.LocalDate;
import java.util.UUID;

/** @param today the clinic's current local date; screens use it instead of the browser's clock */
public record ClinicResponse(UUID id, String name, String timezone, LocalDate today) {}
