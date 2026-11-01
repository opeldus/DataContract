package de.opeldus.datacontract.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ThreeWayMergeTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    private static Map<String, String> fields(String... pairs) {
        var map = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static MergeDocument doc(Map<UUID, Map<String, String>> items) {
        return new MergeDocument(new HashMap<>(), new LinkedHashMap<>(items));
    }

    @Test
    void changesToDifferentFieldsOfOneItemBothSurvive() {
        var base = doc(Map.of(A, fields("type", "INT", "text", "alt")));
        var ours = doc(Map.of(A, fields("type", "INT", "text", "neu")));
        var theirs = doc(Map.of(A, fields("type", "BIGINT", "text", "alt")));

        var result = ThreeWayMerge.merge(base, ours, theirs);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.document().items().get(A)).containsEntry("type", "BIGINT").containsEntry("text", "neu");
    }

    @Test
    void differentChangesToOneFieldConflictAndKeepOurs() {
        var base = doc(Map.of(A, fields("type", "INT")));
        var ours = doc(Map.of(A, fields("type", "DATE")));
        var theirs = doc(Map.of(A, fields("type", "BIGINT")));

        var result = ThreeWayMerge.merge(base, ours, theirs);

        assertThat(result.conflicts()).hasSize(1);
        var conflict = result.conflicts().get(0);
        assertThat(conflict.field()).isEqualTo("type");
        assertThat(conflict.ours()).isEqualTo("DATE");
        assertThat(conflict.theirs()).isEqualTo("BIGINT");
        assertThat(result.document().items().get(A)).containsEntry("type", "DATE");
    }

    @Test
    void identicalChangesOnBothSidesAreNoConflict() {
        var base = doc(Map.of(A, fields("type", "INT")));
        var same = doc(Map.of(A, fields("type", "BIGINT")));

        var result = ThreeWayMerge.merge(base, same, same);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.document().items().get(A)).containsEntry("type", "BIGINT");
    }

    @Test
    void itemsAddedOnEitherSideAreKept() {
        var base = doc(Map.of());
        var ours = doc(Map.of(A, fields("name", "a")));
        var theirs = doc(Map.of(B, fields("name", "b")));

        var result = ThreeWayMerge.merge(base, ours, theirs);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.document().items()).containsOnlyKeys(A, B);
    }

    @Test
    void itemRemovedUpstreamAndUntouchedHereIsRemoved() {
        var base = doc(Map.of(A, fields("name", "a")));
        var ours = doc(Map.of(A, fields("name", "a")));

        var result = ThreeWayMerge.merge(base, ours, doc(Map.of()));

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.document().items()).isEmpty();
    }

    @Test
    void itemRemovedUpstreamButChangedHereConflictsAndStays() {
        var base = doc(Map.of(A, fields("name", "a")));
        var ours = doc(Map.of(A, fields("name", "geändert")));

        var result = ThreeWayMerge.merge(base, ours, doc(Map.of()));

        assertThat(result.conflicts()).hasSize(1);
        assertThat(result.conflicts().get(0).field()).isNull();
        assertThat(result.conflicts().get(0).theirsItem()).isNull();
        assertThat(result.document().items()).containsKey(A);
    }

    @Test
    void itemRemovedHereAndUntouchedUpstreamStaysRemoved() {
        var base = doc(Map.of(A, fields("name", "a")));
        var theirs = doc(Map.of(A, fields("name", "a")));

        var result = ThreeWayMerge.merge(base, doc(Map.of()), theirs);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.document().items()).isEmpty();
    }

    @Test
    void itemRemovedHereButChangedUpstreamConflictsAndStaysRemoved() {
        var base = doc(Map.of(A, fields("name", "a")));
        var theirs = doc(Map.of(A, fields("name", "geändert")));

        var result = ThreeWayMerge.merge(base, doc(Map.of()), theirs);

        assertThat(result.conflicts()).hasSize(1);
        assertThat(result.conflicts().get(0).theirsItem()).containsEntry("name", "geändert");
        assertThat(result.document().items()).isEmpty();
    }

    @Test
    void headerFieldsMergeLikeItemFields() {
        var base = new MergeDocument(new HashMap<>(fields("team", "a", "domain", "x")), new LinkedHashMap<>());
        var ours = new MergeDocument(new HashMap<>(fields("team", "b", "domain", "x")), new LinkedHashMap<>());
        var theirs = new MergeDocument(new HashMap<>(fields("team", "a", "domain", "y")), new LinkedHashMap<>());

        var result = ThreeWayMerge.merge(base, ours, theirs);

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.document().header()).containsEntry("team", "b").containsEntry("domain", "y");
    }

    @Test
    void valueClearedOnOneSideCountsAsAChange() {
        var base = new HashMap<String, String>();
        base.put("text", "alt");
        var ours = new HashMap<String, String>();
        ours.put("text", "alt");
        var theirs = new HashMap<String, String>();
        theirs.put("text", null);

        var result = ThreeWayMerge.merge(doc(Map.of(A, base)), doc(Map.of(A, ours)), doc(Map.of(A, theirs)));

        assertThat(result.conflicts()).isEmpty();
        assertThat(result.document().items().get(A).get("text")).isNull();
    }
}
