package de.opeldus.datacontract.ddl;

import de.opeldus.datacontract.mapping.Mapping;
import de.opeldus.datacontract.mapping.MappingRule;
import de.opeldus.datacontract.validation.ColumnType;
import de.opeldus.datacontract.validation.ValidationIssue;
import de.opeldus.datacontract.validation.ValidationIssue.Severity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Compares a mapping's target with the DDL in Git, without any database connection. Conflicts with an existing table
 * block the review; a table without DDL only warns, since it may simply not exist yet. The DDL reflects one branch, so
 * in a release, where the DDL may still be on its way, conflicts only warn as well.
 */
@Component
public class DdlComparison {

    private final DdlIndex index;

    public DdlComparison(DdlIndex index) {
        this.index = index;
    }

    public List<ValidationIssue> compare(Mapping mapping) {
        if (!index.isAvailable() || mapping.getTargetTable() == null) {
            return List.of();
        }
        var conflictSeverity = mapping.getReleaseId() == null ? Severity.ERROR : Severity.WARNING;
        var issues = new ArrayList<ValidationIssue>();
        var lookup = index.find(mapping.getTargetTable());
        if (lookup.table() == null) {
            var message = lookup.ambiguousCandidates().isEmpty()
                    ? "Für die Zieltabelle '" + mapping.getTargetTable() + "' gibt es keine DDL."
                    : "Die Zieltabelle '" + mapping.getTargetTable() + "' ist nicht eindeutig: "
                            + String.join(", ", lookup.ambiguousCandidates()) + ". Bitte mit Schema angeben.";
            issues.add(issue(Severity.WARNING, "DDL_TABLE_UNKNOWN", message, null, "targetTable"));
            return issues;
        }
        var table = lookup.table();
        var mapped = new HashSet<String>();
        for (var rule : mapping.getRules()) {
            if (rule.getTargetColumn() == null) {
                continue;
            }
            var name = rule.getTargetColumn().toUpperCase(Locale.ROOT);
            mapped.add(name);
            var column = table.columns().stream().filter(candidate -> candidate.name().equals(name)).findFirst();
            if (column.isEmpty()) {
                issues.add(issue(conflictSeverity, "DDL_COLUMN_MISSING",
                        "Die Spalte '" + rule.getTargetColumn() + "' fehlt in der DDL von " + table.name() + ".",
                        rule, "targetColumn"));
            } else {
                compareColumn(rule, column.get(), conflictSeverity, issues);
            }
        }
        var unmapped = table.columns().stream()
                .map(DdlSchema.Column::name)
                .filter(name -> !mapped.contains(name))
                .toList();
        if (!unmapped.isEmpty()) {
            issues.add(issue(Severity.WARNING, "DDL_COLUMN_UNMAPPED",
                    "Diese Spalten aus " + table.name() + " haben keine Regel: " + String.join(", ", unmapped) + ".",
                    null, null));
        }
        return issues;
    }

    private void compareColumn(MappingRule rule, DdlSchema.Column column, Severity conflictSeverity,
            List<ValidationIssue> issues) {
        if (rule.getTargetType() != null) {
            var difference = ColumnType.parse(column.type()).compareTo(ColumnType.parse(rule.getTargetType()));
            if (difference == ColumnType.Difference.VARIANT) {
                issues.add(issue(Severity.WARNING, "DDL_TYPE_VARIANT",
                        "Die DDL nutzt " + column.type() + ", das Mapping " + rule.getTargetType() + ".", rule, "targetType"));
            } else if (difference != ColumnType.Difference.SAME) {
                issues.add(issue(conflictSeverity, "DDL_TYPE_MISMATCH",
                        "Die DDL nutzt " + column.type() + ", das Mapping " + rule.getTargetType() + ".", rule, "targetType"));
            }
        }
        if (rule.isRequired() && column.nullable()) {
            issues.add(issue(Severity.WARNING, "DDL_NULLABLE",
                    "Das Feld ist im Mapping Pflicht, in der DDL aber ohne NOT NULL.", rule, "required"));
        } else if (!rule.isRequired() && !column.nullable()) {
            issues.add(issue(Severity.WARNING, "DDL_NOT_NULL",
                    "Die DDL verlangt NOT NULL, im Mapping ist das Feld aber keine Pflicht.", rule, "required"));
        }
        if (rule.isPrimaryKey() != column.primaryKey()) {
            issues.add(issue(Severity.WARNING, "DDL_PRIMARY_KEY",
                    column.primaryKey() ? "Das Feld ist in der DDL Primärschlüssel, im Mapping nicht."
                            : "Das Feld ist im Mapping Primärschlüssel, in der DDL nicht.",
                    rule, "primaryKey"));
        }
    }

    private static ValidationIssue issue(Severity severity, String code, String message, MappingRule rule, String field) {
        return new ValidationIssue(severity, code, message, rule == null ? null : rule.getId(), field);
    }
}
