package de.opeldus.datacontract.release;

import static de.opeldus.datacontract.common.ApiExceptions.conflict;
import static de.opeldus.datacontract.common.ApiExceptions.notFound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.opeldus.datacontract.artifact.GeneratedArtifact;
import de.opeldus.datacontract.integration.IntegrationTaskPlanner;
import de.opeldus.datacontract.mapping.Mapping;
import de.opeldus.datacontract.mapping.MappingRepository;
import de.opeldus.datacontract.mapping.PublishedVersion;
import de.opeldus.datacontract.mapping.PublishedVersionRepository;
import de.opeldus.datacontract.product.DataProduct;
import de.opeldus.datacontract.product.DataProductRepository;
import de.opeldus.datacontract.review.ReviewEvent;
import de.opeldus.datacontract.review.ReviewEventRepository;
import de.opeldus.datacontract.review.ReviewEventType;
import de.opeldus.datacontract.review.ReviewStatus;
import de.opeldus.datacontract.review.ReviewSubject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import org.springframework.stereotype.Component;

/**
 * Carries a published state into the releases that build on it. A release without its own copy just receives the files
 * on its branch. A release with its own copy gets the change merged field by field; if that alters anything, the copy
 * needs a new review.
 */
@Component
public class ReleaseForwarder {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SYSTEM_ACTOR = "system";

    private final ReleasePlanRepository releases;
    private final MappingRepository mappings;
    private final DataProductRepository products;
    private final PublishedVersionRepository versions;
    private final MergeConflictRepository conflicts;
    private final ReviewEventRepository events;
    private final IntegrationTaskPlanner tasks;
    private final MappingMergeAdapter mappingAdapter;
    private final ProductMergeAdapter productAdapter;
    private final ReleaseScopes scopes;

    ReleaseForwarder(ReleasePlanRepository releases, MappingRepository mappings, DataProductRepository products,
            PublishedVersionRepository versions, MergeConflictRepository conflicts, ReviewEventRepository events,
            IntegrationTaskPlanner tasks, MappingMergeAdapter mappingAdapter, ProductMergeAdapter productAdapter,
            ReleaseScopes scopes) {
        this.releases = releases;
        this.mappings = mappings;
        this.products = products;
        this.versions = versions;
        this.conflicts = conflicts;
        this.events = events;
        this.tasks = tasks;
        this.mappingAdapter = mappingAdapter;
        this.productAdapter = productAdapter;
        this.scopes = scopes;
    }

    public void contractPublished(Mapping mapping, String version, List<GeneratedArtifact> artifacts, String actor) {
        forward(mapping.getReleaseId(), mapping, version, artifacts, actor, mappingAdapter,
                (key, scope) -> mappings.findByContractKeyAndScopeKey(key, scope));
        mappings.flush();
    }

    public void productPublished(DataProduct product, String version, List<GeneratedArtifact> artifacts, String actor) {
        forward(product.getReleaseId(), product, version, artifacts, actor, productAdapter,
                (key, scope) -> products.findByProductKeyAndScopeKey(key, scope));
        products.flush();
    }

    private <T extends ReviewSubject> void forward(UUID scope, T upstream, String version,
            List<GeneratedArtifact> artifacts, String actor, MergeAdapter<T> adapter,
            BiFunction<UUID, String, Optional<T>> rowIn) {
        var children = scope == null
                ? releases.findByBaseIdIsNullAndState(ReleaseState.OPEN)
                : releases.findByBaseIdAndState(scope, ReleaseState.OPEN);
        for (var child : children) {
            var copy = rowIn.apply(upstream.getKey(), Scope.of(child.getId()));
            if (copy.isPresent()) {
                merge(copy.get(), upstream, version, adapter);
            } else {
                tasks.planForward(upstream.getKey(), child.getBranch(), artifacts,
                        "Nachführen aus " + scopes.branchOf(scope) + ": Version " + version, actor);
                forward(child.getId(), upstream, version, artifacts, actor, adapter, rowIn);
            }
        }
    }

    private <T extends ReviewSubject> void merge(T copy, T upstream, String version, MergeAdapter<T> adapter) {
        if (version.equals(copy.getUpstreamVersion())) {
            return;
        }
        var base = copy.getUpstreamVersion() == null
                ? Optional.<PublishedVersion>empty()
                : versions.findById(new PublishedVersion.Key(copy.getKey(), copy.getUpstreamVersion()));
        var baseDocument = base.map(PublishedVersion::getSnapshot)
                .map(adapter::fromSnapshot)
                .orElseGet(MergeDocument::empty);
        var ours = adapter.toDocument(copy);
        var result = ThreeWayMerge.merge(baseDocument, ours, adapter.toDocument(upstream));
        for (var found : result.conflicts()) {
            var label = adapter.label(found.itemId(), found.field(), ours, found.theirsItem());
            conflicts.save(new MergeConflict(copy.getId(), found.itemId(), found.field(), label, found.ours(),
                    found.theirs(), found.theirsItem() == null ? null : write(found.theirsItem()), version));
        }
        var changed = !result.document().equals(ours) || !result.conflicts().isEmpty();
        adapter.apply(copy, result.document());
        copy.rebaseOn(version, versions.findById(new PublishedVersion.Key(copy.getKey(), version))
                .map(PublishedVersion::getCommitId)
                .orElse(null));
        if (changed && !copy.getStatus().isEditable()) {
            copy.setStatus(ReviewStatus.DRAFT);
        }
        copy.touch();
        var summary = "Version " + version + " übernommen"
                + (result.conflicts().isEmpty() ? "." : ", offene Konflikte: " + result.conflicts().size() + ".");
        events.save(new ReviewEvent(copy.getId(), ReviewEventType.FORWARD_MERGED, SYSTEM_ACTOR, summary));
    }

    public void resolve(UUID conflictId, UUID subjectId, boolean takeTheirs, String actor) {
        var found = conflicts.findById(conflictId)
                .filter(candidate -> candidate.getSubjectId().equals(subjectId))
                .orElseThrow(() -> notFound("Konflikt nicht gefunden."));
        if (found.getState() != MergeConflict.State.OPEN) {
            throw conflict("Dieser Konflikt ist bereits geklärt.");
        }
        if (takeTheirs) {
            var mapping = mappings.findById(subjectId);
            if (mapping.isPresent()) {
                applyTheirs(mapping.get(), found, mappingAdapter);
            } else {
                applyTheirs(products.findById(subjectId).orElseThrow(() -> notFound("Nicht gefunden.")), found, productAdapter);
            }
        }
        found.resolve(actor);
        events.save(new ReviewEvent(subjectId, ReviewEventType.EDITED, actor,
                "Konflikt geklärt: " + found.getLabel() + (takeTheirs ? " (Stand aus dem Basisrelease)" : " (eigener Stand)")));
    }

    private <T extends ReviewSubject> void applyTheirs(T subject, MergeConflict found, MergeAdapter<T> adapter) {
        if (!subject.getStatus().isEditable()) {
            throw conflict("Im Status " + subject.getStatus() + " kann das nicht bearbeitet werden.");
        }
        var document = adapter.toDocument(subject);
        if (found.getItemId() == null) {
            document.header().put(found.getField(), found.getTheirs());
        } else if (found.getField() != null) {
            var item = new LinkedHashMap<>(document.items().getOrDefault(found.getItemId(), Map.of()));
            item.put(found.getField(), found.getTheirs());
            document.items().put(found.getItemId(), item);
        } else if (found.getTheirsItem() == null) {
            document.items().remove(found.getItemId());
        } else {
            document.items().put(found.getItemId(), read(found.getTheirsItem()));
        }
        adapter.apply(subject, document);
        subject.touch();
    }

    private static String write(Map<String, String> item) {
        try {
            return JSON.writeValueAsString(item);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, String> read(String json) {
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
