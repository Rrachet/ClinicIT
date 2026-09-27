package com.clinicit.identity.domain;

public enum Role {
    /** Runs the clinic: manages staff and doctors, and can do everything the front desk can. */
    ADMIN,
    /** Front desk: patients, appointments and the queue for every doctor in the clinic. */
    RECEPTIONIST,
    /** Sees and runs only their own appointments and queue. */
    DOCTOR;

    public String authority() {
        return "ROLE_" + name();
    }
}
