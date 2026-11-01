package de.opeldus.datacontract.validation;

import static de.opeldus.datacontract.validation.ValidationIssue.error;
import static de.opeldus.datacontract.validation.ValidationIssue.warning;

import de.opeldus.datacontract.artifact.OdcsSchemaValidator;
import de.opeldus.datacontract.artifact.OdcsContractGenerator;
import de.opeldus.datacontract.catalog.CatalogSearch;
import de.opeldus.datacontract.mapping.Mapping;
import de.opeldus.datacontract.mapping.MappingRule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MappingValidator {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$]*$");

    private final CatalogSearch catalog;
    private final OdcsContractGenerator odcsGenerator;
    private final OdcsSchemaValidator odcsSchema;

    public MappingValidator(CatalogSearch catalog, OdcsContractGenerator odcsGenerator,
            OdcsSchemaValidator odcsSchema) {
        this.catalog = catalog;
        this.odcsGenerator = odcsGenerator;
        this.odcsSchema = odcsSchema;
    }

    public List<ValidationIssue> validate(Mapping mapping) {
        var issues = new ArrayList<ValidationIssue>();
        validateHeader(mapping, issues);
        var targets = new HashMap<String, Integer>();
        for (var rule : mapping.getRules()) {
            if (rule.getTargetColumn() != null) {
                targets.merge(rule.getTargetColumn().toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        for (var rule : mapping.getRules()) {
            validateRule(rule, targets, issues);
        }
        if (issues.stream().noneMatch(issue -> issue.severity() == ValidationIssue.Severity.ERROR)) {
            for (var message : odcsSchema.validate(odcsGenerator.toDocument(mapping))) {
                issues.add(error("ODCS_SCHEMA", "Der erzeugte Contract verletzt ODCS: " + message, null, null));
            }
        }
        return issues;
    }

    private void validateHeader(Mapping mapping, List<ValidationIssue> issues) {
        if (mapping.getTargetTable() == null) {
            issues.add(error("TARGET_TABLE_MISSING", "Die Zieltabelle fehlt.", null, "targetTable"));
        }
        if (mapping.getOwnerTeam() == null) {
            issues.add(error("OWNER_MISSING", "Das verantwortliche Team fehlt.", null, "ownerTeam"));
        }
        if (mapping.getDomain() == null) {
            issues.add(error("DOMAIN_MISSING", "Die Domäne fehlt.", null, "domain"));
        }
        if (mapping.getRules().isEmpty()) {
            issues.add(error("NO_RULES", "Das Mapping enthält noch keine Regeln.", null, null));
        }
        var version = mapping.getContractVersion();
        if (!SemanticVersion.isValid(version)) {
            issues.add(error("VERSION_INVALID", "Die Version muss das Format 1.2.3 haben.", null, "contractVersion"));
        } else if (mapping.getStatus().isEditable() && mapping.getPublishedVersion() != null
                && SemanticVersion.compare(version, mapping.getPublishedVersion()) <= 0) {
            issues.add(error("VERSION_NOT_INCREASED",
                    "Die Version muss höher sein als die veröffentlichte Version " + mapping.getPublishedVersion() + ".",
                    null, "contractVersion"));
        }
    }

    private void validateRule(MappingRule rule, HashMap<String, Integer> targets, List<ValidationIssue> issues) {
        var id = rule.getId();
        var target = rule.getTargetColumn();
        if (target == null) {
            issues.add(error("TARGET_MISSING", "Das Zielfeld fehlt.", id, "targetColumn"));
        } else {
            if (!IDENTIFIER.matcher(target).matches()) {
                issues.add(error("TARGET_INVALID", "'" + target + "' ist kein gültiger Spaltenname.", id,
                        "targetColumn"));
            }
            if (targets.getOrDefault(target.toLowerCase(Locale.ROOT), 0) > 1) {
                issues.add(error("TARGET_DUPLICATE", "Das Zielfeld '" + target + "' ist mehrfach belegt.", id,
                        "targetColumn"));
            }
        }

        var targetFamily = TypeFamily.of(rule.getTargetType());
        if (rule.getTargetType() == null) {
            issues.add(error("TYPE_MISSING", "Der Zieltyp fehlt.", id, "targetType"));
        } else if (targetFamily.isEmpty()) {
            issues.add(warning("TYPE_UNKNOWN", "Der Typ '" + rule.getTargetType() + "' ist nicht bekannt.", id,
                    "targetType"));
        }
        if (rule.isPrimaryKey() && !rule.isRequired()) {
            issues.add(warning("PK_NOT_REQUIRED", "Ein Primärschlüssel sollte Pflichtfeld sein.", id, "required"));
        }
        if (rule.getRequirementKey() == null) {
            issues.add(warning("REQUIREMENT_MISSING", "Es ist keine Anforderung verknüpft.", id, "requirementKey"));
        }

        var hasSource = rule.getSourceDataset() != null && rule.getSourceColumn() != null;
        if (!hasSource) {
            if (rule.getTransformation() == null) {
                issues.add(error("SOURCE_MISSING", "Quelle oder Transformationsregel fehlt.", id, "sourceColumn"));
            }
            return;
        }
        if (!catalog.datasetExists(rule.getSourceDataset())) {
            issues.add(warning("SOURCE_DATASET_UNKNOWN",
                    "Die Quelle '" + rule.getSourceDataset() + "' ist nicht im Katalog.", id, "sourceDataset"));
            return;
        }
        var column = catalog.findColumn(rule.getSourceDataset(), rule.getSourceColumn());
        if (column.isEmpty()) {
            issues.add(error("SOURCE_COLUMN_UNKNOWN",
                    "Die Spalte '" + rule.getSourceColumn() + "' gibt es in '" + rule.getSourceDataset() + "' nicht.",
                    id, "sourceColumn"));
            return;
        }
        var sourceFamily = TypeFamily.of(column.get().nativeType());
        if (rule.getTransformation() == null && targetFamily.isPresent() && sourceFamily.isPresent()
                && !targetFamily.get().acceptsWithoutTransformation(sourceFamily.get())) {
            issues.add(warning("TYPE_MISMATCH",
                    "Quelltyp " + column.get().nativeType() + " passt nicht zu " + rule.getTargetType()
                            + ". Bitte eine Transformationsregel angeben.",
                    id, "targetType"));
        }
        if (rule.isRequired() && column.get().nullable() && rule.getTransformation() == null) {
            issues.add(warning("REQUIRED_FROM_NULLABLE",
                    "Das Zielfeld ist Pflicht, die Quellspalte darf aber leer sein.", id, "required"));
        }
    }
}
