package de.opeldus.datacontract.change;

import static de.opeldus.datacontract.change.ChangeImpact.BREAKING;
import static de.opeldus.datacontract.change.ChangeImpact.CONTENT;
import static de.opeldus.datacontract.change.ChangeImpact.DOCUMENTATION;

import de.opeldus.datacontract.change.ChangeReport.AffectedProduct;
import de.opeldus.datacontract.change.ChangeReport.Change;
import de.opeldus.datacontract.change.ContractSnapshot.Rule;
import de.opeldus.datacontract.mapping.PublishedVersion;
import de.opeldus.datacontract.mapping.PublishedVersionRepository;
import de.opeldus.datacontract.mapping.Mapping;
import de.opeldus.datacontract.product.PortDirection;
import de.opeldus.datacontract.release.ReleaseScopes;
import de.opeldus.datacontract.review.ReviewStatus;
import de.opeldus.datacontract.validation.ColumnType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ContractChangeAnalyzer {

    private final PublishedVersionRepository versions;
    private final ReleaseScopes scopes;

    public ContractChangeAnalyzer(PublishedVersionRepository versions, ReleaseScopes scopes) {
        this.versions = versions;
        this.scopes = scopes;
    }

    public ChangeReport analyze(Mapping mapping) {
        var published = mapping.getPublishedVersion();
        var affected = affectedProducts(mapping.getKey(), mapping.getReleaseId());
        if (published == null) {
            return new ChangeReport(null, true, List.of(), affected);
        }
        if (mapping.getStatus() == ReviewStatus.PUBLISHED) {
            return new ChangeReport(published, true, List.of(), affected);
        }
        var snapshot = versions.findById(new PublishedVersion.Key(mapping.getKey(), published))
                .map(PublishedVersion::getSnapshot)
                .orElse(null);
        if (snapshot == null) {
            return new ChangeReport(published, false, List.of(), affected);
        }
        var changes = compare(ContractSnapshot.fromJson(snapshot), ContractSnapshot.of(mapping));
        changes.sort(Comparator.comparing(Change::impact).reversed());
        return new ChangeReport(published, true, changes, affected);
    }

    static List<Change> compare(ContractSnapshot before, ContractSnapshot after) {
        var changes = new ArrayList<Change>();
        if (!Objects.equals(before.targetTable(), after.targetTable())) {
            changes.add(new Change(null, "TABLE_CHANGED", BREAKING, null, "Die Zieltabelle wurde geändert.",
                    before.targetTable(), after.targetTable()));
        }
        header(changes, "DOMAIN_CHANGED", "Die Domäne wurde geändert.", before.domain(), after.domain());
        header(changes, "OWNER_CHANGED", "Das verantwortliche Team wurde geändert.", before.ownerTeam(), after.ownerTeam());
        header(changes, "DESCRIPTION_CHANGED", "Der Zweck wurde geändert.", before.description(), after.description());

        var remaining = new LinkedHashMap<UUID, Rule>();
        before.rules().forEach(rule -> remaining.put(rule.id(), rule));
        var unmatched = new ArrayList<Rule>();
        for (var rule : after.rules()) {
            var previous = remaining.remove(rule.id());
            if (previous != null) {
                compareRule(previous, rule, changes);
            } else {
                unmatched.add(rule);
            }
        }
        // A re-imported or re-pasted rule has a new id, but it is still the same column.
        for (var rule : unmatched) {
            var previous = remaining.values().stream()
                    .filter(candidate -> sameColumn(candidate.targetColumn(), rule.targetColumn()))
                    .findFirst();
            if (previous.isPresent()) {
                remaining.remove(previous.get().id());
                compareRule(previous.get(), rule, changes);
            } else {
                changes.add(new Change(rule.id(), "COLUMN_ADDED", CONTENT, rule.targetColumn(),
                        "Das Feld ist neu.", null, rule.targetType()));
            }
        }
        for (var rule : remaining.values()) {
            changes.add(new Change(null, "COLUMN_REMOVED", BREAKING, rule.targetColumn(),
                    "Das Feld wurde entfernt.", rule.targetType(), null));
        }
        return changes;
    }

    private static void compareRule(Rule before, Rule after, List<Change> changes) {
        var id = after.id();
        var column = after.targetColumn();
        if (!sameColumn(before.targetColumn(), after.targetColumn())) {
            changes.add(new Change(id, "COLUMN_RENAMED", BREAKING, column, "Das Feld wurde umbenannt.",
                    before.targetColumn(), after.targetColumn()));
        }
        compareType(before, after, changes);
        if (before.required() != after.required()) {
            changes.add(after.required()
                    ? new Change(id, "NOW_REQUIRED", CONTENT, column, "Das Feld ist jetzt Pflicht.", "optional", "Pflicht")
                    : new Change(id, "NO_LONGER_REQUIRED", BREAKING, column,
                            "Das Feld kann jetzt leer sein, darauf sind Abnehmer nicht vorbereitet.", "Pflicht", "optional"));
        }
        if (before.primaryKey() != after.primaryKey()) {
            changes.add(new Change(id, "PRIMARY_KEY_CHANGED", BREAKING, column,
                    after.primaryKey() ? "Das Feld gehört jetzt zum Primärschlüssel."
                            : "Das Feld gehört nicht mehr zum Primärschlüssel.",
                    String.valueOf(before.primaryKey()), String.valueOf(after.primaryKey())));
        }
        var sourceBefore = source(before);
        var sourceAfter = source(after);
        if (!Objects.equals(sourceBefore, sourceAfter)) {
            changes.add(new Change(id, "SOURCE_CHANGED", CONTENT, column, "Die Quelle wurde geändert.", sourceBefore, sourceAfter));
        }
        if (!Objects.equals(before.transformation(), after.transformation())) {
            changes.add(new Change(id, "TRANSFORMATION_CHANGED", CONTENT, column, "Die Transformation wurde geändert.",
                    before.transformation(), after.transformation()));
        }
        if (!Objects.equals(before.description(), after.description())) {
            changes.add(new Change(id, "RULE_DESCRIPTION_CHANGED", DOCUMENTATION, column, "Die Beschreibung wurde geändert.",
                    before.description(), after.description()));
        }
        if (!Objects.equals(before.requirementKey(), after.requirementKey())) {
            changes.add(new Change(id, "REQUIREMENT_CHANGED", DOCUMENTATION, column, "Die Anforderung wurde geändert.",
                    before.requirementKey(), after.requirementKey()));
        }
    }

    private static void compareType(Rule before, Rule after, List<Change> changes) {
        if (Objects.equals(before.targetType(), after.targetType())) {
            return;
        }
        var id = after.id();
        var column = after.targetColumn();
        var difference = ColumnType.parse(before.targetType()).compareTo(ColumnType.parse(after.targetType()));
        var change = switch (difference) {
            case INCOMPATIBLE -> new Change(id, "TYPE_INCOMPATIBLE", BREAKING, column, "Der Typ ist nicht mehr kompatibel.",
                    before.targetType(), after.targetType());
            case NARROWED -> new Change(id, "TYPE_NARROWED", BREAKING, column, "Der Typ wurde eingeschränkt.",
                    before.targetType(), after.targetType());
            case WIDENED -> new Change(id, "TYPE_WIDENED", CONTENT, column, "Der Typ wurde erweitert.",
                    before.targetType(), after.targetType());
            case VARIANT -> new Change(id, "TYPE_VARIANT", CONTENT, column, "Der Typ wurde geändert. Bitte die Auswirkung prüfen.",
                    before.targetType(), after.targetType());
            case SAME -> new Change(id, "TYPE_SPELLING", DOCUMENTATION, column, "Der Typ ist nur anders geschrieben.",
                    before.targetType(), after.targetType());
        };
        changes.add(change);
    }

    private static void header(List<Change> changes, String code, String message, String before, String after) {
        if (!Objects.equals(before, after)) {
            changes.add(new Change(null, code, DOCUMENTATION, null, message, before, after));
        }
    }

    private static boolean sameColumn(String left, String right) {
        return left != null && right != null
                && left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT));
    }

    private static String source(Rule rule) {
        return rule.sourceDataset() == null && rule.sourceColumn() == null
                ? null
                : rule.sourceDataset() + "." + rule.sourceColumn();
    }

    /** Follows the data flow from the contract through products and the contracts they produce. */
    private List<AffectedProduct> affectedProducts(UUID contractId, UUID releaseId) {
        var allProducts = scopes.effectiveProducts(releaseId).values();
        var affected = new ArrayList<AffectedProduct>();
        var seenProducts = new HashSet<UUID>();
        var seenContracts = new HashSet<UUID>();
        var queue = new ArrayDeque<UUID>();
        queue.add(contractId);
        seenContracts.add(contractId);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            for (var product : allProducts) {
                var port = product.ports(PortDirection.INPUT).stream()
                        .filter(input -> current.equals(input.getContractKey()))
                        .findFirst();
                if (port.isEmpty() || !seenProducts.add(product.getKey())) {
                    continue;
                }
                var direct = current.equals(contractId);
                affected.add(new AffectedProduct(product.getId(), product.getProductName(), product.getName(),
                        direct ? port.get().getContractVersion() : null, direct));
                for (var output : product.ports(PortDirection.OUTPUT)) {
                    if (output.getContractKey() != null && seenContracts.add(output.getContractKey())) {
                        queue.add(output.getContractKey());
                    }
                }
            }
        }
        return affected;
    }
}
