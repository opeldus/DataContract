package de.opeldus.datacontract.mapping;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "mapping_rule")
public class MappingRule {

    /** Stable across edits, so comments and diffs keep pointing at the same rule. */
    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mapping_id")
    private Mapping mapping;

    private int sortOrder;
    private String sourceDataset;
    private String sourceColumn;
    private String targetColumn;
    private String targetType;
    private boolean required;
    private boolean primaryKey;
    private String transformation;
    private String description;
    private String requirementKey;

    protected MappingRule() {}

    public MappingRule(Mapping mapping) {
        this.mapping = mapping;
    }

    public UUID getId() { return id; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getSourceDataset() { return sourceDataset; }
    public void setSourceDataset(String sourceDataset) { this.sourceDataset = sourceDataset; }
    public String getSourceColumn() { return sourceColumn; }
    public void setSourceColumn(String sourceColumn) { this.sourceColumn = sourceColumn; }
    public String getTargetColumn() { return targetColumn; }
    public void setTargetColumn(String targetColumn) { this.targetColumn = targetColumn; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public boolean isPrimaryKey() { return primaryKey; }
    public void setPrimaryKey(boolean primaryKey) { this.primaryKey = primaryKey; }
    public String getTransformation() { return transformation; }
    public void setTransformation(String transformation) { this.transformation = transformation; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getRequirementKey() { return requirementKey; }
    public void setRequirementKey(String requirementKey) { this.requirementKey = requirementKey; }
}
