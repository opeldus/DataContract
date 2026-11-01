package de.opeldus.datacontract.release;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "release_plan")
public class ReleasePlan {

    @Id
    private UUID id = UUID.randomUUID();

    private String name;
    private String title;

    /** The release this one builds on; null means the mainline. */
    private UUID baseId;

    @Enumerated(EnumType.STRING)
    private ReleaseState state = ReleaseState.OPEN;

    private String branch;

    @Column(updatable = false)
    private String createdBy;

    @Column(updatable = false)
    private Instant createdAt = Instant.now();

    private Instant releasedAt;
    private String releasedCommit;

    @Version
    private long version;

    protected ReleasePlan() {}

    public ReleasePlan(String name, String title, UUID baseId, String createdBy) {
        this.name = name;
        this.title = title;
        this.baseId = baseId;
        this.branch = "release/" + name;
        this.createdBy = createdBy;
    }

    public void markReleased(String commit) {
        state = ReleaseState.RELEASED;
        releasedAt = Instant.now();
        releasedCommit = commit;
    }

    public void rebaseOn(UUID newBase) {
        baseId = newBase;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getTitle() { return title; }
    public UUID getBaseId() { return baseId; }
    public ReleaseState getState() { return state; }
    public String getBranch() { return branch; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReleasedAt() { return releasedAt; }
    public String getReleasedCommit() { return releasedCommit; }
}
