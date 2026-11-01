package de.opeldus.datacontract.release;

import de.opeldus.datacontract.change.ContractSnapshot;
import de.opeldus.datacontract.mapping.Mapping;
import de.opeldus.datacontract.mapping.MappingRule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
class MappingMergeAdapter implements MergeAdapter<Mapping> {

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("targetTable", "Zieltabelle"), Map.entry("domain", "Domäne"), Map.entry("ownerTeam", "Team"),
            Map.entry("description", "Zweck"), Map.entry("sourceDataset", "Quelle"),
            Map.entry("sourceColumn", "Quellfeld"), Map.entry("targetColumn", "Zielfeld"),
            Map.entry("targetType", "Zieltyp"), Map.entry("required", "Pflicht"),
            Map.entry("primaryKey", "Primärschlüssel"), Map.entry("transformation", "Transformation"),
            Map.entry("ruleDescription", "Beschreibung"), Map.entry("requirementKey", "Anforderung"));

    @Override
    public MergeDocument toDocument(Mapping mapping) {
        return document(ContractSnapshot.of(mapping));
    }

    @Override
    public MergeDocument fromSnapshot(String json) {
        return document(ContractSnapshot.fromJson(json));
    }

    private static MergeDocument document(ContractSnapshot snapshot) {
        var header = new LinkedHashMap<String, String>();
        header.put("targetTable", snapshot.targetTable());
        header.put("domain", snapshot.domain());
        header.put("ownerTeam", snapshot.ownerTeam());
        header.put("description", snapshot.description());
        var items = new LinkedHashMap<UUID, Map<String, String>>();
        for (var rule : snapshot.rules()) {
            var fields = new LinkedHashMap<String, String>();
            fields.put("sourceDataset", rule.sourceDataset());
            fields.put("sourceColumn", rule.sourceColumn());
            fields.put("targetColumn", rule.targetColumn());
            fields.put("targetType", rule.targetType());
            fields.put("required", String.valueOf(rule.required()));
            fields.put("primaryKey", String.valueOf(rule.primaryKey()));
            fields.put("transformation", rule.transformation());
            fields.put("ruleDescription", rule.description());
            fields.put("requirementKey", rule.requirementKey());
            items.put(rule.id(), fields);
        }
        return new MergeDocument(header, items);
    }

    @Override
    public void apply(Mapping mapping, MergeDocument document) {
        var header = document.header();
        mapping.setTargetTable(header.get("targetTable"));
        mapping.setDomain(header.get("domain"));
        mapping.setOwnerTeam(header.get("ownerTeam"));
        mapping.setDescription(header.get("description"));
        var existing = mapping.getRules().stream()
                .collect(Collectors.toMap(MappingRule::getLineageId, Function.identity()));
        var updated = new ArrayList<MappingRule>();
        for (var item : document.items().entrySet()) {
            var rule = existing.get(item.getKey());
            if (rule == null) {
                rule = new MappingRule(mapping);
                rule.setLineageId(item.getKey());
            }
            var fields = item.getValue();
            rule.setSortOrder(updated.size());
            rule.setSourceDataset(fields.get("sourceDataset"));
            rule.setSourceColumn(fields.get("sourceColumn"));
            rule.setTargetColumn(fields.get("targetColumn"));
            rule.setTargetType(fields.get("targetType"));
            rule.setRequired(Boolean.parseBoolean(fields.get("required")));
            rule.setPrimaryKey(Boolean.parseBoolean(fields.get("primaryKey")));
            rule.setTransformation(fields.get("transformation"));
            rule.setDescription(fields.get("ruleDescription"));
            rule.setRequirementKey(fields.get("requirementKey"));
            updated.add(rule);
        }
        mapping.getRules().clear();
        mapping.getRules().addAll(updated);
    }

    @Override
    public String label(UUID itemId, String field, MergeDocument document, Map<String, String> theirsItem) {
        var fieldLabel = field == null ? null : LABELS.getOrDefault(field, field);
        if (itemId == null) {
            return fieldLabel;
        }
        var fields = document.items().get(itemId);
        var column = fields != null ? fields.get("targetColumn") : theirsItem != null ? theirsItem.get("targetColumn") : null;
        var name = column != null ? column : "Regel";
        return fieldLabel == null ? name : name + ": " + fieldLabel;
    }
}
