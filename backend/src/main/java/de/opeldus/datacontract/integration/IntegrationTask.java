package de.opeldus.datacontract.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "integration_task")
class IntegrationTask {

    enum Kind { ISSUE_LINK, ISSUE_UNLINK, DOCUMENTATION_PAGE, GIT_FORWARD }

    enum State { PENDING, DONE, FAILED, SUPERSEDED }

    private static final int MAX_ATTEMPTS = 8;
    private static final long MAX_BACKOFF_MINUTES = 60;

    @Id
    UUID id = UUID.randomUUID();

    UUID subjectId;

    @Enumerated(EnumType.STRING)
    Kind kind;

    String target;
    String payload;

    @Enumerated(EnumType.STRING)
    State state = State.PENDING;

    int attempts;
    String lastError;
    Instant nextAttemptAt = Instant.now();

    @Column(updatable = false)
    Instant createdAt = Instant.now();

    Instant updatedAt = Instant.now();

    protected IntegrationTask() {}

    IntegrationTask(UUID subjectId, Kind kind, String target, String payload) {
        this.subjectId = subjectId;
        this.kind = kind;
        this.target = target;
        this.payload = payload;
    }

    void succeeded() {
        state = State.DONE;
        lastError = null;
        updatedAt = Instant.now();
    }

    void failed(String error) {
        attempts++;
        lastError = error.length() <= 1000 ? error : error.substring(0, 1000);
        updatedAt = Instant.now();
        if (attempts >= MAX_ATTEMPTS) {
            state = State.FAILED;
        } else {
            nextAttemptAt = Instant.now().plus(Duration.ofMinutes(Math.min(1L << (attempts - 1), MAX_BACKOFF_MINUTES)));
        }
    }

    void retry() {
        state = State.PENDING;
        attempts = 0;
        nextAttemptAt = Instant.now();
        updatedAt = Instant.now();
    }

    void supersede() {
        state = State.SUPERSEDED;
        updatedAt = Instant.now();
    }
}
