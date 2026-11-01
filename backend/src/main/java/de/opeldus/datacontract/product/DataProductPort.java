package de.opeldus.datacontract.product;

import de.opeldus.datacontract.mapping.Mapping;
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private DataProduct product;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private PortDirection direction;

    private int sortOrder;
    private String name;

    /** The mapping that generates the referenced contract. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id")
    private Mapping contract;

    private String contractVersion;

    protected DataProductPort() {}

    public DataProductPort(DataProduct product, PortDirection direction) {
        this.product = product;
        this.direction = direction;
    }

    public UUID getId() { return id; }
    public DataProduct getProduct() { return product; }
    public PortDirection getDirection() { return direction; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Mapping getContract() { return contract; }
    public void setContract(Mapping contract) { this.contract = contract; }
    public String getContractVersion() { return contractVersion; }
    public void setContractVersion(String contractVersion) { this.contractVersion = contractVersion; }
}
