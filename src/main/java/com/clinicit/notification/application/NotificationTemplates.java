package com.clinicit.notification.application;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The complete set of message texts. Deliberately fixed and minimal: clinic name, token,
 * time and the status link. Never the patient's name, doctor's specialty, reason for visit,
 * or anything clinical; there is no way to pass such data in.
 */
public final class NotificationTemplates {

    private static final int CLINIC_NAME_MAX = 60;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private NotificationTemplates() {}

    public static String appointmentConfirmed(String clinicName, LocalDateTime scheduledAt) {
        return "%s: your appointment on %s at %s is confirmed."
                .formatted(clinic(clinicName), DAY.format(scheduledAt), TIME.format(scheduledAt));
    }

    public static String joinedQueue(String clinicName, int token, String statusLink) {
        return "%s: you're checked in. Your token is #%d. Follow your place in the queue: %s"
                .formatted(clinic(clinicName), token, statusLink);
    }

    public static String nearTurn(String clinicName, int token, String statusLink) {
        return "%s: token #%d, it's nearly your turn. Please stay close to the waiting area. %s"
                .formatted(clinic(clinicName), token, statusLink);
    }

    public static String called(String clinicName, int token) {
        return "%s: token #%d, it's your turn. Please go in now.".formatted(clinic(clinicName), token);
    }

    private static String clinic(String name) {
        String clean = name == null ? "Clinic" : name.replaceAll("\\s+", " ").trim();
        return clean.length() > CLINIC_NAME_MAX ? clean.substring(0, CLINIC_NAME_MAX) : clean;
    }
}
