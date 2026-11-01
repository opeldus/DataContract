package de.opeldus.datacontract.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogSearch {

    private static final int MAX_SUGGESTIONS = 20;

    private final CatalogDatasetRepository datasets;
    private final CatalogColumnRepository columns;

    CatalogSearch(CatalogDatasetRepository datasets, CatalogColumnRepository columns) {
        this.datasets = datasets;
        this.columns = columns;
    }

    @Transactional(readOnly = true)
    public List<DatasetSuggestion> searchDatasets(String query) {
        return datasets.search(escape(query), PageRequest.of(0, MAX_SUGGESTIONS)).stream()
                .map(dataset -> new DatasetSuggestion(dataset.qualifiedName, dataset.platform, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ColumnSuggestion> searchColumns(String dataset, String query) {
        return columns.search(dataset, escape(query), PageRequest.of(0, MAX_SUGGESTIONS)).stream()
                .map(column -> new ColumnSuggestion(column.name, column.nativeType, column.nullable, column.description, null))
                .toList();
    }

    public boolean datasetExists(String dataset) {
        return datasets.existsById(dataset);
    }

    public Optional<ColumnSuggestion> findColumn(String dataset, String name) {
        return columns.find(dataset, name)
                .map(column -> new ColumnSuggestion(column.name, column.nativeType, column.nullable, column.description, null));
    }

    private static String escape(String query) {
        return query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** {@code plannedIn} names the contract that is going to create this, if it is not live yet. */
    public record DatasetSuggestion(String name, String platform, String plannedIn) {}

    public record ColumnSuggestion(String name, String nativeType, boolean nullable, String description, String plannedIn) {}
}
