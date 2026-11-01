package de.opeldus.datacontract.release;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Merges field by field, so two people editing different fields of one rule never collide. Where both sides changed the
 * same thing differently, "ours" stays in place and a conflict is reported for a person to decide.
 */
public final class ThreeWayMerge {

    private ThreeWayMerge() {}

    /** {@code itemId} and {@code field} are null for what they do not apply to; {@code theirsItem} is null if upstream removed the item. */
    public record Conflict(UUID itemId, String field, String ours, String theirs, Map<String, String> theirsItem) {}

    public record Result(MergeDocument document, List<Conflict> conflicts) {}

    public static Result merge(MergeDocument base, MergeDocument ours, MergeDocument theirs) {
        var conflicts = new ArrayList<Conflict>();
        var header = mergeFields(null, base.header(), ours.header(), theirs.header(), conflicts);

        var items = new LinkedHashMap<UUID, Map<String, String>>();
        for (var entry : ours.items().entrySet()) {
            var id = entry.getKey();
            var mine = entry.getValue();
            var original = base.items().get(id);
            var upstream = theirs.items().get(id);
            if (original == null) {
                if (upstream == null) {
                    items.put(id, mine);
                } else {
                    items.put(id, mergeFields(id, Map.of(), mine, upstream, conflicts));
                }
            } else if (upstream != null) {
                items.put(id, mergeFields(id, original, mine, upstream, conflicts));
            } else if (mine.equals(original)) {
                continue;
            } else {
                conflicts.add(new Conflict(id, null, null, null, null));
                items.put(id, mine);
            }
        }
        for (var entry : theirs.items().entrySet()) {
            var id = entry.getKey();
            if (ours.items().containsKey(id)) {
                continue;
            }
            var original = base.items().get(id);
            if (original == null) {
                items.put(id, entry.getValue());
            } else if (!original.equals(entry.getValue())) {
                conflicts.add(new Conflict(id, null, null, null, entry.getValue()));
            }
        }
        return new Result(new MergeDocument(header, items), conflicts);
    }

    private static Map<String, String> mergeFields(UUID itemId, Map<String, String> base, Map<String, String> ours,
            Map<String, String> theirs, List<Conflict> conflicts) {
        var fields = new LinkedHashMap<>(ours);
        var names = new ArrayList<>(ours.keySet());
        theirs.keySet().stream().filter(name -> !ours.containsKey(name)).forEach(names::add);
        var theirsCopy = new HashMap<>(theirs);
        for (var name : names) {
            var original = base.get(name);
            var mine = ours.get(name);
            var upstream = theirs.get(name);
            if (Objects.equals(mine, upstream) || Objects.equals(upstream, original)) {
                continue;
            }
            if (Objects.equals(mine, original)) {
                fields.put(name, upstream);
            } else {
                conflicts.add(new Conflict(itemId, name, mine, upstream, theirsCopy));
            }
        }
        return fields;
    }
}
