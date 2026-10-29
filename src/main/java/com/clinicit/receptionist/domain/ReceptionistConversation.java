package com.clinicit.receptionist.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One patient's chat with the AI receptionist. {@code messages} is the Claude API transcript
 * as JSON, replayed unchanged on the next turn. Deleted after a day: it holds names and phones.
 */
@Entity
@Table(name = "receptionist_conversations")
public class ReceptionistConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "clinic_id", nullable = false, updatable = false)
    private UUID clinicId;

    @Column(nullable = false, columnDefinition = "text")
    private String messages = "[]";

    @Column(name = "user_turns", nullable = false)
    private int userTurns;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReceptionistConversation() {}

    public ReceptionistConversation(UUID clinicId, Instant now) {
        this.clinicId = clinicId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getClinicId() { return clinicId; }
    public String getMessages() { return messages; }
    public int getUserTurns() { return userTurns; }

    public void record(String messages, Instant now) {
        this.messages = messages;
        this.userTurns++;
        this.updatedAt = now;
    }
}
