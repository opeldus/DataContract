package de.opeldus.datacontract.product;

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

    public DataProduct(String name, String productName, String createdBy) {
        this.name = name;
        this.productName = productName;
        this.createdBy = createdBy;
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
    public String getPublishedVersion() { return publishedVersion; }
    public String getPublishedCommit() { return publishedCommit; }
    @Override public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public List<DataProductPort> getPorts() { return ports; }
}
