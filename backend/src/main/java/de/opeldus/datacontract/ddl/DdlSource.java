package de.opeldus.datacontract.ddl;

import de.opeldus.datacontract.ddl.DdlSchema.DdlFile;
import java.util.List;

/** Extension point for where DDL files come from. */
public interface DdlSource {

    Snapshot load();

    /** {@code revision} identifies the state, e.g. a commit, so the UI can say what was compared. */
    record Snapshot(String revision, List<DdlFile> files) {}
}
