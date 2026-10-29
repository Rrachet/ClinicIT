package com.clinicit.checkin.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Something a patient can come in for, as the clinic names it ("General check-up", "Child
 * (paediatrics)"). The patient picks one at self check-in; the doctors linked to it (or any
 * doctor, for {@link #isAnyDoctor()}) can see them. Operational, not a diagnosis.
 */
@Entity
@Table(name = "visit_types")
public class VisitType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "clinic_id", nullable = false, updatable = false)
    private UUID clinicId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 200)
    private String description;

    @Column(name = "any_doctor", nullable = false)
    private boolean anyDoctor;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getClinicId() { return clinicId; }
    public void setClinicId(UUID clinicId) { this.clinicId = clinicId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isAnyDoctor() { return anyDoctor; }
    public void setAnyDoctor(boolean anyDoctor) { this.anyDoctor = anyDoctor; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
