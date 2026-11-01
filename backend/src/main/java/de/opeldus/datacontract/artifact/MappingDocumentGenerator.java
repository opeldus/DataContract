package de.opeldus.datacontract.artifact;

import static de.opeldus.datacontract.artifact.Yaml.putIfPresent;

import de.opeldus.datacontract.mapping.Mapping;
import de.opeldus.datacontract.mapping.MappingRule;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Keeps what ODCS cannot express, e.g. the source column split and the display name, diffable in Git. */
@Component
public class MappingDocumentGenerator implements ArtifactGenerator {

    @Override
    public GeneratedArtifact generate(Mapping mapping) {
        var document = new LinkedHashMap<String, Object>();
        document.put("kind", "MappingDocument");
        document.put("id", mapping.getId().toString());
        document.put("name", mapping.getName());
        document.put("contract", mapping.getContractName());
        document.put("contractVersion", mapping.getContractVersion());
        putIfPresent(document, "targetTable", mapping.getTargetTable());
        document.put("rules", mapping.getRules().stream().map(this::rule).toList());
        return new GeneratedArtifact("mappings/" + mapping.getContractName() + ".yaml", Yaml.write(document));
    }

    private Map<String, Object> rule(MappingRule rule) {
        var document = new LinkedHashMap<String, Object>();
        document.put("id", rule.getId().toString());
        putIfPresent(document, "sourceDataset", rule.getSourceDataset());
        putIfPresent(document, "sourceColumn", rule.getSourceColumn());
        putIfPresent(document, "targetColumn", rule.getTargetColumn());
        putIfPresent(document, "targetType", rule.getTargetType());
        document.put("required", rule.isRequired());
        document.put("primaryKey", rule.isPrimaryKey());
        putIfPresent(document, "transformation", rule.getTransformation());
        putIfPresent(document, "description", rule.getDescription());
        putIfPresent(document, "requirementKey", rule.getRequirementKey());
        return document;
    }
}
