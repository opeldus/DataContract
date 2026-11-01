package de.opeldus.datacontract.release;

import java.util.UUID;

/** Where a row lives: on the mainline, in a release, or archived after a release replaced it. */
public final class Scope {

    public static final String MAIN = "main";
    private static final String ARCHIVED_PREFIX = "archived:";

    private Scope() {}

    public static String of(UUID releaseId) {
        return releaseId == null ? MAIN : releaseId.toString();
    }

    public static String archived(UUID rowId) {
        return ARCHIVED_PREFIX + rowId;
    }

    public static boolean isArchived(String scopeKey) {
        return scopeKey.startsWith(ARCHIVED_PREFIX);
    }
}
