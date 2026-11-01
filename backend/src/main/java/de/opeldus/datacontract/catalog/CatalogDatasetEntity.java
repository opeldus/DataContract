package de.opeldus.datacontract.catalog;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "catalog_dataset")
class CatalogDatasetEntity {

    @Id
    String qualifiedName;

    String urn;
    String platform;

    @OneToMany(mappedBy = "dataset", cascade = CascadeType.ALL, orphanRemoval = true)
    List<CatalogColumnEntity> columns = new ArrayList<>();

    protected CatalogDatasetEntity() {}

    CatalogDatasetEntity(String qualifiedName, String urn, String platform) {
        this.qualifiedName = qualifiedName;
        this.urn = urn;
        this.platform = platform;
    }
}
