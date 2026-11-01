package de.opeldus.datacontract.review;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Append-only history of a mapping; it doubles as the audit trail and the comment thread. */
@Entity
@Table(name = "review_event")
public class ReviewEvent {

    @Id
    private UUID id = UUID.randomUUID();

    private UUID mappingId;

    @Enumerated(EnumType.STRING)
    private ReviewEventType type;

    private String actor;
    private String comment;
    private UUID ruleId;
    private String contentHash;
    private Instant createdAt = Instant.now();

    protected ReviewEvent() {}

    public ReviewEvent(UUID mappingId, ReviewEventType type, String actor) {
        this(mappingId, type, actor, null);
    }

    public ReviewEvent(UUID mappingId, ReviewEventType type, String actor, String comment) {
        this.mappingId = mappingId;
        this.type = type;
        this.actor = actor;
        this.comment = comment;
    }

    public ReviewEvent withRuleId(UUID ruleId) {
        this.ruleId = ruleId;
        return this;
    }

    public ReviewEvent withContentHash(String contentHash) {
        this.contentHash = contentHash;
        return this;
    }

    public UUID getId() { return id; }
    public UUID getMappingId() { return mappingId; }
    public ReviewEventType getType() { return type; }
    public String getActor() { return actor; }
    public String getComment() { return comment; }
    public UUID getRuleId() { return ruleId; }
    public String getContentHash() { return contentHash; }
    public Instant getCreatedAt() { return createdAt; }
}
