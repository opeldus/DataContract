package de.opeldus.datacontract.catalog;

import de.opeldus.datacontract.catalog.CatalogSearch.ColumnSuggestion;
import de.opeldus.datacontract.catalog.CatalogSearch.DatasetSuggestion;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogSearch search;

    public CatalogController(CatalogSearch search) {
        this.search = search;
    }

    @GetMapping("/datasets")
    public List<DatasetSuggestion> datasets(@RequestParam(defaultValue = "") String q) {
        return search.searchDatasets(q);
    }

    @GetMapping("/columns")
    public List<ColumnSuggestion> columns(@RequestParam String dataset, @RequestParam(defaultValue = "") String q) {
        return search.searchColumns(dataset, q);
    }
}
