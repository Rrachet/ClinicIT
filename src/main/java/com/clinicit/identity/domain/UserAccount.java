package com.clinicit.identity.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "clinic_id", nullable = false, updatable = false)
    private UUID clinicId;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private Role role;

    @Column(name = "doctor_profile_id", updatable = false)
    private UUID doctorProfileId;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserAccount() {
        // for JPA
    }

    public UserAccount(UUID clinicId, String email, String passwordHash, String fullName, Role role, UUID doctorProfileId) {
        this.clinicId = clinicId;
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.doctorProfileId = doctorProfileId;
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Actor toActor() {
        return new Actor(id, clinicId, role, doctorProfileId);
    }

    public void disable() {
        enabled = false;
    }

    public UUID getId() { return id; }
    public UUID getClinicId() { return clinicId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFullName() { return fullName; }
    public Role getRole() { return role; }
    public UUID getDoctorProfileId() { return doctorProfileId; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
}
