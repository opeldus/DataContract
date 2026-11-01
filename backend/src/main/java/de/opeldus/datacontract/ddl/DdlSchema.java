package de.opeldus.datacontract.ddl;

import java.util.List;
import java.util.Map;

/** Tables as they result from all DDL statements; keys and names are upper-case. Warnings name what was not understood. */
public record DdlSchema(Map<String, Table> tables, List<String> warnings) {

    public record Table(String name, List<Column> columns) {}

    public record Column(String name, String type, boolean nullable, boolean primaryKey) {}

    /** A single DDL file; {@code path} decides the order in which migrations apply. */
    public record DdlFile(String path, String content) {}
}
