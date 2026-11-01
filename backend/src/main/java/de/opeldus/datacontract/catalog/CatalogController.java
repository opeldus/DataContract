package de.opeldus.datacontract.catalog;

import de.opeldus.datacontract.catalog.CatalogSearch.ColumnSuggestion;
import de.opeldus.datacontract.catalog.CatalogSearch.DatasetSuggestion;
import de.opeldus.datacontract.release.PlannedSchema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Suggests what is live according to the catalog, followed by what the release is about to create. */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogSearch search;
    private final PlannedSchema planned;

    public CatalogController(CatalogSearch search, PlannedSchema planned) {
        this.search = search;
        this.planned = planned;
    }

    @GetMapping("/datasets")
    @Transactional(readOnly = true)
    public List<DatasetSuggestion> datasets(@RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) UUID release) {
        var suggestions = new ArrayList<>(search.searchDatasets(q));
        var seen = new HashSet<String>();
        suggestions.forEach(suggestion -> seen.add(suggestion.name().toLowerCase(Locale.ROOT)));
        for (var table : planned.searchTables(q, release)) {
            var name = table.name().toLowerCase(Locale.ROOT);
            if (seen.stream().noneMatch(known -> known.equals(name) || known.endsWith("." + name)) && seen.add(name)) {
                suggestions.add(new DatasetSuggestion(table.name(), null, table.origin()));
            }
        }
        return suggestions;
    }

    @GetMapping("/columns")
    @Transactional(readOnly = true)
    public List<ColumnSuggestion> columns(@RequestParam String dataset, @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) UUID release) {
        var suggestions = new ArrayList<>(search.searchColumns(dataset, q));
        var seen = new HashSet<String>();
        suggestions.forEach(suggestion -> seen.add(suggestion.name().toLowerCase(Locale.ROOT)));
        for (var column : planned.searchColumns(dataset, q, release)) {
            if (seen.add(column.name().toLowerCase(Locale.ROOT))) {
                suggestions.add(new ColumnSuggestion(column.name(), column.type(), true, null, column.origin()));
            }
        }
        return suggestions;
    }
}
