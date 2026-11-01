package de.opeldus.datacontract.release;

import static de.opeldus.datacontract.common.ApiExceptions.badRequest;

import de.opeldus.datacontract.mapping.Mapping;
import de.opeldus.datacontract.mapping.MappingRepository;
import de.opeldus.datacontract.mapping.PublishedVersion;
import de.opeldus.datacontract.mapping.PublishedVersionRepository;
import de.opeldus.datacontract.product.DataProduct;
import de.opeldus.datacontract.product.DataProductRepository;
import de.opeldus.datacontract.publish.GitProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Answers what a release sees. A release only holds what it changes; for everything else it sees the state of its
 * nearest ancestor, up to the mainline. A release is therefore never out of date for items it did not touch.
 */
@Service
public class ReleaseScopes {

    private final ReleasePlanRepository releases;
    private final MappingRepository mappings;
    private final DataProductRepository products;
    private final PublishedVersionRepository versions;
    private final GitProperties git;

    public ReleaseScopes(ReleasePlanRepository releases, MappingRepository mappings, DataProductRepository products,
            PublishedVersionRepository versions, GitProperties git) {
        this.releases = releases;
        this.mappings = mappings;
        this.products = products;
        this.versions = versions;
        this.git = git;
    }

    /** From the release itself up to the mainline, which is represented by null. */
    public List<UUID> lineage(UUID releaseId) {
        var chain = new ArrayList<UUID>();
        var current = releaseId;
        var seen = new HashSet<UUID>();
        while (current != null && seen.add(current)) {
            chain.add(current);
            current = find(current).getBaseId();
        }
        chain.add(null);
        return chain;
    }

    public ReleasePlan find(UUID releaseId) {
        return releases.findById(releaseId).orElseThrow(() -> badRequest("Dieses Release gibt es nicht."));
    }

    public Optional<String> releaseName(UUID releaseId) {
        return releaseId == null ? Optional.empty() : releases.findById(releaseId).map(ReleasePlan::getName);
    }

    public String branchOf(UUID releaseId) {
        return releaseId == null ? git.branch() : find(releaseId).getBranch();
    }

    public Map<UUID, Mapping> effectiveMappings(UUID releaseId) {
        var chain = lineage(releaseId);
        var effective = new LinkedHashMap<UUID, Mapping>();
        var nearestLast = new ArrayList<>(chain);
        Collections.reverse(nearestLast);
        for (var scope : nearestLast) {
            mappings.findByScopeKey(Scope.of(scope)).forEach(mapping -> effective.put(mapping.getKey(), mapping));
        }
        return effective;
    }

    public Map<UUID, DataProduct> effectiveProducts(UUID releaseId) {
        var chain = lineage(releaseId);
        var effective = new LinkedHashMap<UUID, DataProduct>();
        var nearestLast = new ArrayList<>(chain);
        Collections.reverse(nearestLast);
        for (var scope : nearestLast) {
            products.findByScopeKey(Scope.of(scope)).forEach(product -> effective.put(product.getKey(), product));
        }
        return effective;
    }

    /** Versions published in a parallel release stay invisible until that release is on the mainline. */
    public List<String> visibleVersions(UUID subjectKey, UUID releaseId) {
        var chain = lineage(releaseId);
        return versions.findBySubjectKey(subjectKey).stream()
                .filter(version -> chain.contains(version.getReleaseId()))
                .map(PublishedVersion::getVersion)
                .toList();
    }
}
