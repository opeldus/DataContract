package de.opeldus.datacontract.release;

import de.opeldus.datacontract.mapping.Mapping;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Target tables and columns of the contracts a release sees. The catalog only knows what is live, but someone mapping
 * for a release also needs what an earlier release is about to create.
 */
@Service
public class PlannedSchema {

    private final ReleaseScopes scopes;

    public PlannedSchema(ReleaseScopes scopes) {
        this.scopes = scopes;
    }

    public record PlannedTable(String name, String origin) {}

    public record PlannedColumn(String name, String type, String origin) {}

    public List<PlannedTable> searchTables(String query, UUID releaseId) {
        var needle = query.toLowerCase(Locale.ROOT);
        var tables = new ArrayList<PlannedTable>();
        for (var mapping : scopes.effectiveMappings(releaseId).values()) {
            var table = mapping.getTargetTable();
            if (table != null && table.toLowerCase(Locale.ROOT).contains(needle)) {
                tables.add(new PlannedTable(table, origin(mapping)));
            }
        }
        return tables;
    }

    public List<PlannedColumn> searchColumns(String dataset, String query, UUID releaseId) {
        var needle = query.toLowerCase(Locale.ROOT);
        var columns = new ArrayList<PlannedColumn>();
        for (var mapping : scopes.effectiveMappings(releaseId).values()) {
            if (!matches(mapping, dataset)) {
                continue;
            }
            for (var rule : mapping.getRules()) {
                var column = rule.getTargetColumn();
                if (column != null && column.toLowerCase(Locale.ROOT).contains(needle)) {
                    columns.add(new PlannedColumn(column, rule.getTargetType(), origin(mapping)));
                }
            }
        }
        return columns;
    }

    /** {@code self} is the contract being validated, because a contract cannot be its own source of planned data. */
    public Optional<String> tableOrigin(String dataset, UUID releaseId, UUID self) {
        return scopes.effectiveMappings(releaseId).values().stream()
                .filter(mapping -> !mapping.getKey().equals(self) && matches(mapping, dataset))
                .map(PlannedSchema::origin)
                .findFirst();
    }

    public Optional<PlannedColumn> findColumn(String dataset, String column, UUID releaseId, UUID self) {
        for (var mapping : scopes.effectiveMappings(releaseId).values()) {
            if (mapping.getKey().equals(self) || !matches(mapping, dataset)) {
                continue;
            }
            for (var rule : mapping.getRules()) {
                if (column.equalsIgnoreCase(rule.getTargetColumn())) {
                    return Optional.of(new PlannedColumn(rule.getTargetColumn(), rule.getTargetType(), origin(mapping)));
                }
            }
        }
        return Optional.empty();
    }

    /** The catalog qualifies names with platform and schema, a mapping often names only the table. */
    private static boolean matches(Mapping mapping, String dataset) {
        var table = mapping.getTargetTable();
        if (table == null || dataset == null) {
            return false;
        }
        var left = dataset.toLowerCase(Locale.ROOT);
        var right = table.toLowerCase(Locale.ROOT);
        return left.equals(right) || left.endsWith("." + right);
    }

    private static String origin(Mapping mapping) {
        return mapping.getContractName() + " " + mapping.getContractVersion();
    }
}
