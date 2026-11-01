package de.opeldus.datacontract.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "data_product_port")
public class DataProductPort {

    @Id
    private UUID id = UUID.randomUUID();

    /** Shared by the copies of this port across releases, which is how changes are matched up. */
    private UUID lineageId = id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private DataProduct product;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private PortDirection direction;

    private int sortOrder;
    private String name;

    /** Identifies the contract across releases. */
    private UUID contractKey;

    private String contractVersion;

    protected DataProductPort() {}

    public DataProductPort(DataProduct product, PortDirection direction) {
        this.product = product;
        this.direction = direction;
    }

    DataProductPort copyFor(DataProduct target, int order) {
        var copy = new DataProductPort(target, direction);
        copy.lineageId = lineageId;
        copy.sortOrder = order;
        copy.name = name;
        copy.contractKey = contractKey;
        copy.contractVersion = contractVersion;
        return copy;
    }

    public UUID getId() { return id; }
    public UUID getLineageId() { return lineageId; }
    public void setLineageId(UUID lineageId) { this.lineageId = lineageId; }
    public DataProduct getProduct() { return product; }
    public PortDirection getDirection() { return direction; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public UUID getContractKey() { return contractKey; }
    public void setContractKey(UUID contractKey) { this.contractKey = contractKey; }
    public String getContractVersion() { return contractVersion; }
    public void setContractVersion(String contractVersion) { this.contractVersion = contractVersion; }
}
