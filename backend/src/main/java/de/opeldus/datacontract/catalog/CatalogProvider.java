package de.opeldus.datacontract.catalog;

import java.util.List;

/** Extension point for metadata sources; the result is copied into the local search index. */
public interface CatalogProvider {

    List<Dataset> fetchDatasets();

    record Dataset(String qualifiedName, String urn, String platform, List<Column> columns) {}

    record Column(String name, String nativeType, boolean nullable, String description) {}
}
