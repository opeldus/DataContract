package de.opeldus.datacontract.validation;

import static de.opeldus.datacontract.validation.ValidationIssue.error;
import static de.opeldus.datacontract.validation.ValidationIssue.warning;

import de.opeldus.datacontract.artifact.OdcsSchemaValidator;
import de.opeldus.datacontract.artifact.OdcsContractGenerator;
import de.opeldus.datacontract.catalog.CatalogSearch;
import de.opeldus.datacontract.change.ChangeImpact;
import de.opeldus.datacontract.change.ContractChangeAnalyzer;
import de.opeldus.datacontract.ddl.DdlComparison;
import de.opeldus.datacontract.integration.RequirementDirectory;
import de.opeldus.datacontract.mapping.Mapping;
import de.opeldus.datacontract.mapping.PublishedVersion;
import de.opeldus.datacontract.mapping.PublishedVersionRepository;
import de.opeldus.datacontract.release.MergeConflict;
import de.opeldus.datacontract.release.MergeConflictRepository;
import de.opeldus.datacontract.release.PlannedSchema;
import de.opeldus.datacontract.release.ReleaseScopes;
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
    private final ContractChangeAnalyzer changes;
    private final DdlComparison ddl;
    private final RequirementDirectory requirements;
    private final ReleaseScopes scopes;
    private final PlannedSchema planned;
    private final MergeConflictRepository conflicts;
    private final PublishedVersionRepository versions;

    public MappingValidator(CatalogSearch catalog, OdcsContractGenerator odcsGenerator,
            OdcsSchemaValidator odcsSchema, ContractChangeAnalyzer changes, DdlComparison ddl,
            RequirementDirectory requirements, ReleaseScopes scopes, PlannedSchema planned,
            MergeConflictRepository conflicts, PublishedVersionRepository versions) {
        this.catalog = catalog;
        this.odcsGenerator = odcsGenerator;
        this.odcsSchema = odcsSchema;
        this.changes = changes;
        this.ddl = ddl;
        this.requirements = requirements;
        this.scopes = scopes;
        this.planned = planned;
        this.conflicts = conflicts;
        this.versions = versions;
    }

    public List<ValidationIssue> validate(Mapping mapping) {
        var issues = new ArrayList<ValidationIssue>();
        validateHeader(mapping, issues);
        var openConflicts = conflicts.countBySubjectIdAndState(mapping.getId(), MergeConflict.State.OPEN);
        if (openConflicts > 0) {
            issues.add(error("MERGE_CONFLICT", "Offene Konflikte mit dem Basisrelease: " + openConflicts + ".", null, null));
        }
        var targets = new HashMap<String, Integer>();
        for (var rule : mapping.getRules()) {
            if (rule.getTargetColumn() != null) {
                targets.merge(rule.getTargetColumn().toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        for (var rule : mapping.getRules()) {
            validateRule(mapping, rule, targets, issues);
        }
        issues.addAll(ddl.compare(mapping));
        validateVersionBump(mapping, issues);
        if (issues.stream().noneMatch(issue -> issue.severity() == ValidationIssue.Severity.ERROR)) {
            for (var message : odcsSchema.validate(odcsGenerator.toDocument(mapping))) {
                issues.add(error("ODCS_SCHEMA", "Der erzeugte Contract verletzt ODCS: " + message, null, null));
            }
        }
        return issues;
    }

    private static ValidationIssue plannedSource(PlannedSchema.PlannedColumn column, java.util.UUID ruleId) {
        return warning("SOURCE_PLANNED", "Die Spalte '" + column.name() + "' ist noch nicht live, sie entsteht mit "
                + column.origin() + ".", ruleId, "sourceColumn");
    }

    private void validateRequirement(MappingRule rule, List<ValidationIssue> issues) {
        var key = rule.getRequirementKey();
        switch (requirements.check(key)) {
            case INVALID_FORMAT -> issues.add(warning("REQUIREMENT_FORMAT",
                    "'" + key + "' sieht nicht wie ein Jira-Schlüssel aus, zum Beispiel DATA-123.", rule.getId(),
                    "requirementKey"));
            case NOT_FOUND -> issues.add(error("REQUIREMENT_NOT_FOUND",
                    "Jira kennt '" + key + "' nicht, oder dem Zugang fehlen die Leserechte.", rule.getId(),
                    "requirementKey"));
            case FOUND, UNKNOWN -> { }
        }
    }

    /** The version number has to tell consumers what the change means for them. */
    private void validateVersionBump(Mapping mapping, List<ValidationIssue> issues) {
        var published = mapping.getPublishedVersion();
        var version = mapping.getContractVersion();
        if (!mapping.getStatus().isEditable() || published == null || !SemanticVersion.isValid(version)
                || !SemanticVersion.isValid(published) || SemanticVersion.compare(version, published) <= 0) {
            return;
        }
        var report = changes.analyze(mapping);
        var bump = SemanticVersion.bump(published, version);
        if (report.highestImpact() == ChangeImpact.BREAKING && bump != SemanticVersion.Bump.MAJOR) {
            issues.add(error("CHANGE_NEEDS_MAJOR",
                    "Inkompatible Änderungen brauchen eine neue Hauptversion, zum Beispiel "
                            + new java.math.BigInteger(published.split("\\.")[0]).add(java.math.BigInteger.ONE) + ".0.0.",
                    null, "contractVersion"));
        } else if (report.highestImpact() == ChangeImpact.CONTENT && bump == SemanticVersion.Bump.PATCH) {
            issues.add(warning("CHANGE_NEEDS_MINOR",
                    "Inhaltliche Änderungen sollten die mittlere Versionsstelle erhöhen.", null, "contractVersion"));
        }
        if (report.highestImpact() == ChangeImpact.BREAKING && !report.affected().isEmpty()) {
            var names = report.affected().stream().map(product -> product.productName()).toList();
            issues.add(warning("BREAKING_AFFECTS_CONSUMERS",
                    "Die inkompatible Änderung betrifft: " + String.join(", ", names) + ".", null, null));
        }
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
        } else if (mapping.getStatus().isEditable()) {
            var highest = scopes.visibleVersions(mapping.getKey(), mapping.getReleaseId()).stream()
                    .filter(SemanticVersion::isValid)
                    .max(SemanticVersion.ORDER);
            if (highest.isPresent() && SemanticVersion.compare(version, highest.get()) <= 0) {
                issues.add(error("VERSION_NOT_INCREASED",
                        "Die Version muss höher sein als die veröffentlichte Version " + highest.get() + ".",
                        null, "contractVersion"));
            } else if (versions.existsById(new PublishedVersion.Key(mapping.getKey(), version))) {
                issues.add(error("VERSION_TAKEN",
                        "Die Version " + version + " wurde bereits in einem anderen Release veröffentlicht.",
                        null, "contractVersion"));
            }
        }
    }

    private void validateRule(Mapping mapping, MappingRule rule, HashMap<String, Integer> targets,
            List<ValidationIssue> issues) {
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
        } else {
            validateRequirement(rule, issues);
        }

        var hasSource = rule.getSourceDataset() != null && rule.getSourceColumn() != null;
        if (!hasSource) {
            if (rule.getTransformation() == null) {
                issues.add(error("SOURCE_MISSING", "Quelle oder Transformationsregel fehlt.", id, "sourceColumn"));
            }
            return;
        }
        var plannedColumn = planned.findColumn(rule.getSourceDataset(), rule.getSourceColumn(), mapping.getReleaseId(), mapping.getKey());
        if (!catalog.datasetExists(rule.getSourceDataset())) {
            var plannedTable = planned.tableOrigin(rule.getSourceDataset(), mapping.getReleaseId(), mapping.getKey());
            if (plannedColumn.isPresent()) {
                issues.add(plannedSource(plannedColumn.get(), id));
            } else if (plannedTable.isPresent()) {
                issues.add(error("SOURCE_COLUMN_UNKNOWN", "Die Spalte '" + rule.getSourceColumn() + "' plant "
                        + plannedTable.get() + " nicht.", id, "sourceColumn"));
            } else {
                issues.add(warning("SOURCE_DATASET_UNKNOWN",
                        "Die Quelle '" + rule.getSourceDataset() + "' ist nicht im Katalog.", id, "sourceDataset"));
            }
            return;
        }
        var column = catalog.findColumn(rule.getSourceDataset(), rule.getSourceColumn());
        if (column.isEmpty() && plannedColumn.isPresent()) {
            issues.add(plannedSource(plannedColumn.get(), id));
            return;
        }
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
