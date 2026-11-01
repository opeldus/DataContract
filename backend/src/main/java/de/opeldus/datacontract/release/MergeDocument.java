package de.opeldus.datacontract.release;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A contract or data product reduced to what merging compares: header fields, and items by their lineage id. */
public record MergeDocument(Map<String, String> header, Map<UUID, Map<String, String>> items) {

    public static MergeDocument empty() {
        return new MergeDocument(new LinkedHashMap<>(), new LinkedHashMap<>());
    }
}
