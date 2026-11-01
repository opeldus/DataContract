package de.opeldus.datacontract.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Copies provider metadata into the local index; if a provider fails, the previous index stays in use. */
@Service
public class CatalogIndexer {

    private static final Logger log = LoggerFactory.getLogger(CatalogIndexer.class);

    private final List<CatalogProvider> providers;
    private final CatalogDatasetRepository datasets;
    private final TransactionTemplate transaction;

    CatalogIndexer(List<CatalogProvider> providers, CatalogDatasetRepository datasets, TransactionTemplate transaction) {
        this.providers = providers;
        this.datasets = datasets;
        this.transaction = transaction;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(initialDelayString = "${app.catalog.refresh-interval}", fixedDelayString = "${app.catalog.refresh-interval}")
    public void refresh() {
        if (providers.isEmpty()) {
            return;
        }
        try {
            var fetched = new LinkedHashMap<String, CatalogProvider.Dataset>();
            for (var provider : providers) {
                for (var dataset : provider.fetchDatasets()) {
                    fetched.put(dataset.qualifiedName(), dataset);
                }
            }
            transaction.executeWithoutResult(status -> replaceIndex(fetched.values()));
            log.info("Catalog index refreshed with {} datasets", fetched.size());
        } catch (RuntimeException exception) {
            log.error("Catalog refresh failed; keeping the previous index", exception);
        }
    }

    private void replaceIndex(Iterable<CatalogProvider.Dataset> fetched) {
        datasets.deleteAll();
        datasets.flush();
        var entities = new ArrayList<CatalogDatasetEntity>();
        for (var dataset : fetched) {
            var entity = new CatalogDatasetEntity(dataset.qualifiedName(), dataset.urn(), dataset.platform());
            for (var column : dataset.columns()) {
                entity.columns.add(new CatalogColumnEntity(entity, column.name(), column.nativeType(), column.nullable(),
                        column.description()));
            }
            entities.add(entity);
        }
        datasets.saveAll(entities);
    }
}
