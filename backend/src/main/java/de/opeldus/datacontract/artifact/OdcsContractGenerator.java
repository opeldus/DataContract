package de.opeldus.datacontract.artifact;

import static de.opeldus.datacontract.artifact.Yaml.putIfPresent;

import de.opeldus.datacontract.mapping.Mapping;
import de.opeldus.datacontract.mapping.MappingRule;
import de.opeldus.datacontract.validation.TypeFamily;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OdcsContractGenerator implements ArtifactGenerator {

    static final String API_VERSION = "v3.2.0";

    @Override
    public GeneratedArtifact generate(Mapping mapping) {
        return new GeneratedArtifact("contracts/" + mapping.getContractName() + "/odcs.yaml",
                Yaml.write(toDocument(mapping)));
    }

    /** The status is always "active": a contract only reaches Git after approval. */
    public Map<String, Object> toDocument(Mapping mapping) {
        var document = new LinkedHashMap<String, Object>();
        document.put("apiVersion", API_VERSION);
        document.put("kind", "DataContract");
        document.put("id", "urn:datacontract:" + mapping.getId());
        document.put("name", mapping.getContractName());
        document.put("version", mapping.getContractVersion());
        document.put("status", "active");
        putIfPresent(document, "domain", mapping.getDomain());
        if (mapping.getDescription() != null) {
            document.put("description", Map.of("purpose", mapping.getDescription()));
        }
        if (mapping.getOwnerTeam() != null) {
            document.put("team", Map.of("name", mapping.getOwnerTeam()));
        }
        document.put("schema", List.of(schemaObject(mapping)));
        return document;
    }

    private Map<String, Object> schemaObject(Mapping mapping) {
        var table = new LinkedHashMap<String, Object>();
        var targetTable = mapping.getTargetTable() != null ? mapping.getTargetTable() : mapping.getContractName();
        table.put("name", targetTable);
        table.put("physicalName", targetTable);
        table.put("logicalType", "object");
        table.put("physicalType", "table");
        var properties = new ArrayList<Map<String, Object>>();
        int primaryKeyPosition = 1;
        for (var rule : mapping.getRules()) {
            if (rule.getTargetColumn() != null) {
                properties.add(property(rule, rule.isPrimaryKey() ? primaryKeyPosition++ : null));
            }
        }
        table.put("properties", properties);
        return table;
    }

    private Map<String, Object> property(MappingRule rule, Integer primaryKeyPosition) {
        var property = new LinkedHashMap<String, Object>();
        property.put("name", rule.getTargetColumn());
        property.put("physicalName", rule.getTargetColumn());
        TypeFamily.of(rule.getTargetType()).ifPresent(family -> property.put("logicalType", family.logicalType()));
        putIfPresent(property, "physicalType", rule.getTargetType());
        putIfPresent(property, "description", rule.getDescription());
        property.put("required", rule.isRequired());
        if (primaryKeyPosition != null) {
            property.put("primaryKey", true);
            property.put("primaryKeyPosition", primaryKeyPosition);
        }
        if (rule.getSourceDataset() != null && rule.getSourceColumn() != null) {
            property.put("transformSourceObjects", List.of(rule.getSourceDataset() + "." + rule.getSourceColumn()));
        }
        putIfPresent(property, "transformLogic", rule.getTransformation());
        var custom = new ArrayList<Map<String, Object>>();
        custom.add(customProperty("mappingRuleId", rule.getId().toString()));
        if (rule.getRequirementKey() != null) {
            custom.add(customProperty("requirementKey", rule.getRequirementKey()));
        }
        property.put("customProperties", custom);
        return property;
    }

    private static Map<String, Object> customProperty(String name, String value) {
        var property = new LinkedHashMap<String, Object>();
        property.put("property", name);
        property.put("value", value);
        return property;
    }
}
