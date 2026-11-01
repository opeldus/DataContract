package de.opeldus.datacontract.release;

import java.util.Map;
import java.util.UUID;

/** Connects one kind of reviewed item to the merge. */
interface MergeAdapter<T> {

    MergeDocument toDocument(T subject);

    MergeDocument fromSnapshot(String json);

    void apply(T subject, MergeDocument document);

    /** A readable name of the conflicting item or field, e.g. "UMSATZ: Zieltyp". */
    String label(UUID itemId, String field, MergeDocument document, Map<String, String> theirsItem);
}
