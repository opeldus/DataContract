package de.opeldus.datacontract.change;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.opeldus.datacontract.mapping.Mapping;
import java.util.List;
import java.util.UUID;

/** Everything about a mapping that change detection compares; stored as JSON with each published version. */
public record ContractSnapshot(String targetTable, String domain, String ownerTeam, String description,
        List<Rule> rules) {

    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public record Rule(UUID id, String sourceDataset, String sourceColumn, String targetColumn, String targetType,
            boolean required, boolean primaryKey, String transformation, String description, String requirementKey) {}

    public static ContractSnapshot of(Mapping mapping) {
        var rules = mapping.getRules().stream()
                .map(rule -> new Rule(rule.getId(), rule.getSourceDataset(), rule.getSourceColumn(),
                        rule.getTargetColumn(), rule.getTargetType(), rule.isRequired(), rule.isPrimaryKey(),
                        rule.getTransformation(), rule.getDescription(), rule.getRequirementKey()))
                .toList();
        return new ContractSnapshot(mapping.getTargetTable(), mapping.getDomain(), mapping.getOwnerTeam(),
                mapping.getDescription(), rules);
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Snapshot could not be written", exception);
        }
    }

    public static ContractSnapshot fromJson(String json) {
        try {
            return JSON.readValue(json, ContractSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Snapshot could not be read", exception);
        }
    }
}
