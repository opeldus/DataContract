package de.opeldus.datacontract.release;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merge_conflict")
public class MergeConflict {

    public enum State { OPEN, RESOLVED }

    @Id
    private UUID id = UUID.randomUUID();

    private UUID subjectId;
    private UUID itemId;
    private String field;
    private String label;
    private String ours;
    private String theirs;

    /** The item as upstream has it, as JSON; null if upstream removed it. */
    private String theirsItem;

    private String upstreamVersion;

    @Enumerated(EnumType.STRING)
    private State state = State.OPEN;

    @Column(updatable = false)
    private Instant createdAt = Instant.now();

    private String resolvedBy;
    private Instant resolvedAt;

    protected MergeConflict() {}

    MergeConflict(UUID subjectId, UUID itemId, String field, String label, String ours, String theirs, String theirsItem,
            String upstreamVersion) {
        this.subjectId = subjectId;
        this.itemId = itemId;
        this.field = field;
        this.label = label;
        this.ours = limit(ours);
        this.theirs = limit(theirs);
        this.theirsItem = theirsItem;
        this.upstreamVersion = upstreamVersion;
    }

    void resolve(String actor) {
        state = State.RESOLVED;
        resolvedBy = actor;
        resolvedAt = Instant.now();
    }

    private static String limit(String value) {
        return value == null || value.length() <= 4000 ? value : value.substring(0, 4000);
    }

    public UUID getId() { return id; }
    public UUID getSubjectId() { return subjectId; }
    public UUID getItemId() { return itemId; }
    public String getField() { return field; }
    public String getLabel() { return label; }
    public String getOurs() { return ours; }
    public String getTheirs() { return theirs; }
    public String getTheirsItem() { return theirsItem; }
    public String getUpstreamVersion() { return upstreamVersion; }
    public State getState() { return state; }
}
