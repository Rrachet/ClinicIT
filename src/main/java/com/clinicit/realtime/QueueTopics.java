package com.clinicit.realtime;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only destinations clients may subscribe to.
 * <ul>
 *   <li>{@code /topic/clinic/{clinicId}/queue}: every queue event of the clinic (front desk)</li>
 *   <li>{@code /topic/clinic/{clinicId}/doctor/{doctorId}/queue}: one doctor's queue</li>
 * </ul>
 */
public final class QueueTopics {

    private static final String UUID_RE = "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})";
    private static final Pattern CLINIC = Pattern.compile("^/topic/clinic/" + UUID_RE + "/queue$");
    private static final Pattern DOCTOR = Pattern.compile("^/topic/clinic/" + UUID_RE + "/doctor/" + UUID_RE + "/queue$");

    private QueueTopics() {}

    public static String clinic(UUID clinicId) {
        return "/topic/clinic/" + clinicId + "/queue";
    }

    public static String doctor(UUID clinicId, UUID doctorId) {
        return "/topic/clinic/" + clinicId + "/doctor/" + doctorId + "/queue";
    }

    /** A parsed, well-formed queue destination. doctorId is null for the clinic-wide topic. */
    public record Target(UUID clinicId, UUID doctorId) {}

    public static Optional<Target> parse(String destination) {
        if (destination == null) {
            return Optional.empty();
        }
        Matcher clinic = CLINIC.matcher(destination);
        if (clinic.matches()) {
            return Optional.of(new Target(UUID.fromString(clinic.group(1)), null));
        }
        Matcher doctor = DOCTOR.matcher(destination);
        if (doctor.matches()) {
            return Optional.of(new Target(UUID.fromString(doctor.group(1)), UUID.fromString(doctor.group(2))));
        }
        return Optional.empty();
    }
}
