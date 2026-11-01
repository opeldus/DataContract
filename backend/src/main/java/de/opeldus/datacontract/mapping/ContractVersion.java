package de.opeldus.datacontract.mapping;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** A contract version that reached Git; data product ports can only pin versions listed here. */
@Entity
@Table(name = "contract_version")
@IdClass(ContractVersion.Key.class)
public class ContractVersion {

    @Id
    private UUID mappingId;

    @Id
    private String version;

    @Column(updatable = false)
    private String commitId;

    @Column(updatable = false)
    private Instant publishedAt = Instant.now();

    /** Null for versions published before snapshots existed. */
    @Column(updatable = false)
    private String snapshot;

    protected ContractVersion() {}

    public ContractVersion(UUID mappingId, String version, String commitId, String snapshot) {
        this.mappingId = mappingId;
        this.version = version;
        this.commitId = commitId;
        this.snapshot = snapshot;
    }

    public UUID getMappingId() { return mappingId; }
    public String getVersion() { return version; }
    public String getCommitId() { return commitId; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getSnapshot() { return snapshot; }

    public record Key(UUID mappingId, String version) implements Serializable {}
}
