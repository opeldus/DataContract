package de.opeldus.datacontract.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "catalog_column")
class CatalogColumnEntity {

    @Id
    UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_name")
    CatalogDatasetEntity dataset;

    String name;
    String nativeType;
    boolean nullable;
    String description;

    protected CatalogColumnEntity() {}

    CatalogColumnEntity(CatalogDatasetEntity dataset, String name, String nativeType, boolean nullable,
            String description) {
        this.dataset = dataset;
        this.name = name;
        this.nativeType = nativeType;
        this.nullable = nullable;
        this.description = description;
    }
}
