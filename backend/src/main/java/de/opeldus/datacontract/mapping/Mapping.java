package de.opeldus.datacontract.mapping;

import de.opeldus.datacontract.release.Scope;
import de.opeldus.datacontract.review.ReviewStatus;
import de.opeldus.datacontract.review.ReviewSubject;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "mapping")
public class Mapping implements ReviewSubject {

    @Id
    private UUID id = UUID.randomUUID();

    private UUID contractKey = id;
    private UUID releaseId;
    private String scopeKey = Scope.MAIN;

    private String name;
    private String contractName;
    private String contractVersion;
    private String domain;
    private String description;
    private String targetTable;
    private String ownerTeam;

    @Enumerated(EnumType.STRING)
    private ReviewStatus status = ReviewStatus.DRAFT;

    private String publishedVersion;

    /** In a release: the state of the base this copy last took over, which is the common ancestor for merging. */
    private String upstreamVersion;

    private String publishedCommit;

    @Column(updatable = false)
    private String createdBy;

    @Column(updatable = false)
    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    @OneToMany(mappedBy = "mapping", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    private List<MappingRule> rules = new ArrayList<>();

    protected Mapping() {}

    public Mapping(String name, String contractName, String createdBy, UUID releaseId) {
        this.name = name;
        this.contractName = contractName;
        this.contractVersion = "1.0.0";
        this.createdBy = createdBy;
        this.releaseId = releaseId;
        this.scopeKey = Scope.of(releaseId);
    }

    /** A copy to work on in a release; it keeps the identity of the contract and of each rule. */
    public Mapping forkInto(UUID targetRelease, String actor) {
        var fork = new Mapping(name, contractName, actor, targetRelease);
        fork.contractKey = contractKey;
        fork.contractVersion = contractVersion;
        fork.domain = domain;
        fork.description = description;
        fork.targetTable = targetTable;
        fork.ownerTeam = ownerTeam;
        fork.publishedVersion = publishedVersion;
        fork.publishedCommit = publishedCommit;
        fork.upstreamVersion = publishedVersion;
        int order = 0;
        for (var rule : rules) {
            fork.rules.add(rule.copyFor(fork, order++));
        }
        return fork;
    }

    public void archive() {
        scopeKey = Scope.archived(id);
        releaseId = null;
    }

    public void moveToMainline() {
        scopeKey = Scope.MAIN;
        releaseId = null;
    }

    @Override
    public void rebaseOn(String version, String commit) {
        // Without an own publish in the release, the base's new state is also what changes are compared against.
        if (java.util.Objects.equals(publishedVersion, upstreamVersion)) {
            publishedVersion = version;
            publishedCommit = commit;
        }
        upstreamVersion = version;
    }

    @Override
    public String getUpstreamVersion() {
        return upstreamVersion;
    }

    @Override
    public boolean isArchived() {
        return Scope.isArchived(scopeKey);
    }

    @Override
    public void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public void markPublished(String commit) {
        status = ReviewStatus.PUBLISHED;
        publishedVersion = contractVersion;
        publishedCommit = commit;
    }

    @Override public UUID getId() { return id; }
    @Override public UUID getKey() { return contractKey; }
    @Override public UUID getReleaseId() { return releaseId; }
    public String getScopeKey() { return scopeKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getContractName() { return contractName; }
    public String getContractVersion() { return contractVersion; }
    public void setContractVersion(String contractVersion) { this.contractVersion = contractVersion; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }
    public String getOwnerTeam() { return ownerTeam; }
    public void setOwnerTeam(String ownerTeam) { this.ownerTeam = ownerTeam; }
    @Override public ReviewStatus getStatus() { return status; }
    @Override public void setStatus(ReviewStatus status) { this.status = status; }
    @Override public String getPublishedVersion() { return publishedVersion; }
    public String getPublishedCommit() { return publishedCommit; }
    @Override public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public List<MappingRule> getRules() { return rules; }
}
