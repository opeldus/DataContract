package de.opeldus.datacontract.ddl;

import de.opeldus.datacontract.ddl.DdlSchema.DdlFile;
import de.opeldus.datacontract.ddl.DdlSchema.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Keeps the parsed DDL in memory; it can be rebuilt at any time, and a failed refresh keeps the previous state. */
@Service
public class DdlIndex {

    private static final Logger log = LoggerFactory.getLogger(DdlIndex.class);

    private final List<DdlSource> sources;
    private final DdlParser parser;
    private volatile DdlSchema schema;
    private volatile String revision;
    private volatile Instant refreshedAt;
    private volatile String lastError;

    public DdlIndex(List<DdlSource> sources, DdlParser parser) {
        this.sources = sources;
        this.parser = parser;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(initialDelayString = "${app.ddl.refresh-interval}", fixedDelayString = "${app.ddl.refresh-interval}")
    public void refresh() {
        if (sources.isEmpty()) {
            return;
        }
        try {
            var files = new ArrayList<DdlFile>();
            var revisions = new ArrayList<String>();
            for (var source : sources) {
                var snapshot = source.load();
                files.addAll(snapshot.files());
                revisions.add(snapshot.revision());
            }
            schema = parser.parse(files);
            revision = String.join(",", revisions);
            refreshedAt = Instant.now();
            lastError = null;
            log.info("DDL index refreshed with {} tables", schema.tables().size());
            schema.warnings().forEach(warning -> log.warn("DDL: {}", warning));
        } catch (RuntimeException exception) {
            lastError = exception.getMessage();
            log.error("DDL refresh failed; keeping the previous state", exception);
        }
    }

    public boolean isAvailable() {
        return schema != null;
    }

    /** Accepts a table with or without schema, as long as the name is unique. */
    public Lookup find(String tableName) {
        var current = schema;
        if (current == null) {
            return new Lookup(null, List.of());
        }
        var key = tableName.toUpperCase(Locale.ROOT);
        var exact = current.tables().get(key);
        if (exact != null) {
            return new Lookup(exact, List.of());
        }
        var candidates = current.tables().keySet().stream().filter(name -> name.endsWith("." + key)).toList();
        return candidates.size() == 1
                ? new Lookup(current.tables().get(candidates.get(0)), List.of())
                : new Lookup(null, candidates);
    }

    public Status status() {
        var current = schema;
        return new Status(!sources.isEmpty(), revision, refreshedAt, current == null ? 0 : current.tables().size(),
                current == null ? List.of() : current.warnings(), lastError);
    }

    public record Lookup(Table table, List<String> ambiguousCandidates) {}

    public record Status(boolean configured, String revision, Instant refreshedAt, int tableCount,
            List<String> warnings, String error) {}
}
