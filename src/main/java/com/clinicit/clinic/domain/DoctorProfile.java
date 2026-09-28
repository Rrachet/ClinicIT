package com.clinicit.clinic.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "doctor_profiles")
public class DoctorProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "clinic_id", nullable = false)
    private UUID clinicId;

    @Column(name = "display_name", nullable = false, length = 150)
    private String displayName;

    @Column(length = 120)
    private String specialization;

    /** Length of one appointment slot (Phase 10 scheduling). */
    @Column(name = "appointment_minutes", nullable = false)
    private int appointmentMinutes = 15;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getClinicId() { return clinicId; }
    public void setClinicId(UUID clinicId) { this.clinicId = clinicId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getSpecialization() { return specialization; }
    public void setSpecialization(String specialization) { this.specialization = specialization; }
    public int getAppointmentMinutes() { return appointmentMinutes; }
    public void setAppointmentMinutes(int appointmentMinutes) { this.appointmentMinutes = appointmentMinutes; }
    public Instant getCreatedAt() { return createdAt; }
}
