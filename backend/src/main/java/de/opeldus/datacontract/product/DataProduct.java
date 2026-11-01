package de.opeldus.datacontract.product;

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
@Table(name = "data_product")
public class DataProduct implements ReviewSubject {

    @Id
    private UUID id = UUID.randomUUID();

    private UUID productKey = id;
    private UUID releaseId;
    private String scopeKey = Scope.MAIN;

    private String name;
    private String productName;
    private String productVersion = "1.0.0";
    private String domain;
    private String productType;
    private String purpose;
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

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    private List<DataProductPort> ports = new ArrayList<>();

    protected DataProduct() {}

    public DataProduct(String name, String productName, String createdBy, UUID releaseId) {
        this.name = name;
        this.productName = productName;
        this.createdBy = createdBy;
        this.releaseId = releaseId;
        this.scopeKey = Scope.of(releaseId);
    }

    /** A copy to work on in a release; it keeps the identity of the product and of each port. */
    public DataProduct forkInto(UUID targetRelease, String actor) {
        var fork = new DataProduct(name, productName, actor, targetRelease);
        fork.productKey = productKey;
        fork.productVersion = productVersion;
        fork.domain = domain;
        fork.productType = productType;
        fork.purpose = purpose;
        fork.ownerTeam = ownerTeam;
        fork.publishedVersion = publishedVersion;
        fork.publishedCommit = publishedCommit;
        fork.upstreamVersion = publishedVersion;
        int order = 0;
        for (var port : ports) {
            fork.ports.add(port.copyFor(fork, order++));
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
        publishedVersion = productVersion;
        publishedCommit = commit;
    }

    public List<DataProductPort> ports(PortDirection direction) {
        return ports.stream().filter(port -> port.getDirection() == direction).toList();
    }

    @Override public UUID getId() { return id; }
    @Override public UUID getKey() { return productKey; }
    @Override public UUID getReleaseId() { return releaseId; }
    public String getScopeKey() { return scopeKey; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProductName() { return productName; }
    public String getProductVersion() { return productVersion; }
    public void setProductVersion(String productVersion) { this.productVersion = productVersion; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getOwnerTeam() { return ownerTeam; }
    public void setOwnerTeam(String ownerTeam) { this.ownerTeam = ownerTeam; }
    @Override public ReviewStatus getStatus() { return status; }
    @Override public void setStatus(ReviewStatus status) { this.status = status; }
    @Override public String getPublishedVersion() { return publishedVersion; }
    public String getPublishedCommit() { return publishedCommit; }
    @Override public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public List<DataProductPort> getPorts() { return ports; }
}
