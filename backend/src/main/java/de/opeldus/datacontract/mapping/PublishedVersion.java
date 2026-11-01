package de.opeldus.datacontract.mapping;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * A version of a contract or data product that reached Git. Ports can only pin versions listed here, and the snapshot
 * is the baseline for change detection and for merging. {@code releaseId} is null once the version is on the mainline.
 */
@Entity
@Table(name = "published_version")
@IdClass(PublishedVersion.Key.class)
public class PublishedVersion {

    @Id
    private UUID subjectKey;

    @Id
    private String version;

    @Column(updatable = false)
    private String commitId;

    @Column(updatable = false)
    private Instant publishedAt = Instant.now();

    /** Null for versions published before snapshots existed. */
    @Column(updatable = false)
    private String snapshot;

    private UUID releaseId;

    protected PublishedVersion() {}

    public PublishedVersion(UUID subjectKey, String version, String commitId, String snapshot, UUID releaseId) {
        this.subjectKey = subjectKey;
        this.version = version;
        this.commitId = commitId;
        this.snapshot = snapshot;
        this.releaseId = releaseId;
    }

    public UUID getSubjectKey() { return subjectKey; }
    public String getVersion() { return version; }
    public String getCommitId() { return commitId; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getSnapshot() { return snapshot; }
    public UUID getReleaseId() { return releaseId; }
    public void moveToMainline() { releaseId = null; }

    public record Key(UUID subjectKey, String version) implements Serializable {}
}
