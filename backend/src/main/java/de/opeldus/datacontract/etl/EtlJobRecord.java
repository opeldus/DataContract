package de.opeldus.datacontract.etl;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "etl_job")
public class EtlJobRecord {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String kind;

    private String category;

    @Column(name = "source_file", nullable = false)
    private String sourceFile;

    @Column(name = "imported_by", nullable = false)
    private String importedBy;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    @Column(nullable = false)
    private String model;

    protected EtlJobRecord() {}

    public EtlJobRecord(String name) {
        this.id = UUID.randomUUID();
        this.name = name;
    }

    public void replaceWith(String kind, String category, String sourceFile, String importedBy, String model) {
        this.kind = kind;
        this.category = category;
        this.sourceFile = sourceFile;
        this.importedBy = importedBy;
        this.importedAt = Instant.now();
        this.model = model;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getKind() {
        return kind;
    }

    public String getCategory() {
        return category;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public String getImportedBy() {
        return importedBy;
    }

    public Instant getImportedAt() {
        return importedAt;
    }

    public String getModel() {
        return model;
    }
}
