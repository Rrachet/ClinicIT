package com.clinicit.schedule.domain;

/** Why a doctor cannot take an appointment at a given time. The name is the API error code. */
public enum Unavailable {
    DAY_OFF("The doctor does not work on this day"),
    OUTSIDE_HOURS("The time is outside the doctor's working hours"),
    ON_BREAK("The time falls in the doctor's break"),
    TIME_OFF("The doctor is on leave or unavailable at this time"),
    IN_THE_PAST("The time has already passed"),
    SLOT_TAKEN("The doctor already has an appointment at this time"),
    NOT_WORKING_TODAY("The doctor is not seeing any more patients today");

    private final String message;

    Unavailable(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
