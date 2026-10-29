package com.clinicit.clinic.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "clinics")
public class Clinic {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 500)
    private String address;

    @Column(nullable = false, length = 64)
    private String timezone = "Asia/Kolkata";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Public code in the clinic's check-in QR code: /check-in/{code}. */
    @Column(name = "check_in_code", nullable = false, length = 32, unique = true)
    private String checkInCode;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (checkInCode == null) {
            checkInCode = newCheckInCode();
        }
    }

    /** 128 random bits, URL-safe: a printed code cannot be guessed from another clinic's. */
    public static String newCheckInCode() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCheckInCode() { return checkInCode; }
    public void setCheckInCode(String checkInCode) { this.checkInCode = checkInCode; }
}
