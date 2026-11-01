package de.opeldus.datacontract.change;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Differences to the last published version. {@code baselineVersion} is null for a contract that was never published;
 * {@code baselineAvailable} is false if that version has no snapshot to compare against.
 */
public record ChangeReport(String baselineVersion, boolean baselineAvailable, List<Change> changes,
        List<AffectedProduct> affected) {

    public record Change(UUID ruleId, String code, ChangeImpact impact, String column, String message, String before,
            String after) {}

    /** {@code pinnedVersion} is null if the product only depends on the contract through another product. */
    public record AffectedProduct(UUID productId, String productName, String productTitle, String pinnedVersion,
            boolean direct) {}

    public ChangeImpact highestImpact() {
        return changes.stream().map(Change::impact).max(Comparator.naturalOrder()).orElse(null);
    }
}
