package de.opeldus.datacontract.mapping;

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
public class Mapping {

    @Id
    private UUID id = UUID.randomUUID();

    private String name;
    private String contractName;
    private String contractVersion;
    private String domain;
    private String description;
    private String targetTable;
    private String ownerTeam;

    @Enumerated(EnumType.STRING)
    private MappingStatus status = MappingStatus.DRAFT;

    private String publishedVersion;
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

    public Mapping(String name, String contractName, String createdBy) {
        this.name = name;
        this.contractName = contractName;
        this.contractVersion = "1.0.0";
        this.createdBy = createdBy;
    }

    public void touch() {
        updatedAt = Instant.now();
    }

    public void markPublished(String commit) {
        status = MappingStatus.PUBLISHED;
        publishedVersion = contractVersion;
        publishedCommit = commit;
    }

    public UUID getId() { return id; }
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
    public MappingStatus getStatus() { return status; }
    public void setStatus(MappingStatus status) { this.status = status; }
    public String getPublishedVersion() { return publishedVersion; }
    public String getPublishedCommit() { return publishedCommit; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public List<MappingRule> getRules() { return rules; }
}
