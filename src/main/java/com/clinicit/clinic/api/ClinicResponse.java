package com.clinicit.clinic.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * @param today       the clinic's current local date; screens use it instead of the browser's clock
 * @param checkInCode public code for the self check-in QR code (/check-in/{code})
 */
public record ClinicResponse(UUID id, String name, String timezone, LocalDate today, String checkInCode) {}
