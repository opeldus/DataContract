package de.opeldus.datacontract.release;

import static de.opeldus.datacontract.common.ApiExceptions.conflict;
import static de.opeldus.datacontract.common.ApiExceptions.notFound;

import de.opeldus.datacontract.artifact.ArtifactService;
import de.opeldus.datacontract.artifact.GeneratedArtifact;
import de.opeldus.datacontract.artifact.OdpsProductGenerator;
import de.opeldus.datacontract.integration.IntegrationTaskPlanner;
import de.opeldus.datacontract.mapping.Mapping;
import de.opeldus.datacontract.mapping.MappingRepository;
import de.opeldus.datacontract.mapping.PublishedVersionRepository;
import de.opeldus.datacontract.product.DataProduct;
import de.opeldus.datacontract.product.DataProductRepository;
import de.opeldus.datacontract.publish.GitPublisher;
import de.opeldus.datacontract.release.ReleaseDtos.CreateReleaseRequest;
import de.opeldus.datacontract.release.ReleaseDtos.ReleaseItem;
import de.opeldus.datacontract.release.ReleaseDtos.ReleaseView;
import de.opeldus.datacontract.review.ReviewEvent;
import de.opeldus.datacontract.review.ReviewEventRepository;
import de.opeldus.datacontract.review.ReviewEventType;
import de.opeldus.datacontract.review.ReviewStatus;
import de.opeldus.datacontract.security.CurrentUser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReleaseService {

    private final ReleasePlanRepository releases;
    private final ReleaseScopes scopes;
    private final MappingRepository mappings;
    private final DataProductRepository products;
    private final PublishedVersionRepository versions;
    private final MergeConflictRepository conflicts;
    private final ReviewEventRepository events;
    private final GitPublisher git;
    private final ArtifactService artifacts;
    private final OdpsProductGenerator productGenerator;
    private final IntegrationTaskPlanner tasks;
    private final ReleaseForwarder forwarder;
    private final CurrentUser currentUser;

    ReleaseService(ReleasePlanRepository releases, ReleaseScopes scopes, MappingRepository mappings,
            DataProductRepository products, PublishedVersionRepository versions, MergeConflictRepository conflicts,
            ReviewEventRepository events, GitPublisher git, ArtifactService artifacts,
            OdpsProductGenerator productGenerator, IntegrationTaskPlanner tasks, ReleaseForwarder forwarder,
            CurrentUser currentUser) {
        this.releases = releases;
        this.scopes = scopes;
        this.mappings = mappings;
        this.products = products;
        this.versions = versions;
        this.conflicts = conflicts;
        this.events = events;
        this.git = git;
        this.artifacts = artifacts;
        this.productGenerator = productGenerator;
        this.tasks = tasks;
        this.forwarder = forwarder;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<ReleaseView> list() {
        return releases.findAll().stream()
                .sorted(Comparator.comparing(ReleasePlan::getState).thenComparing(ReleasePlan::getCreatedAt))
                .map(this::view)
                .toList();
    }

    @Transactional
    public ReleaseView create(CreateReleaseRequest request, String actor) {
        if (releases.existsByName(request.name())) {
            throw conflict("Ein Release mit dem Namen '" + request.name() + "' gibt es bereits.");
        }
        if (request.baseId() != null && scopes.find(request.baseId()).getState() != ReleaseState.OPEN) {
            throw conflict("Ein abgeschlossenes Release kann keine Basis sein.");
        }
        var release = releases.saveAndFlush(new ReleasePlan(request.name(), request.title().trim(), request.baseId(), actor));
        git.createBranch(release.getBranch(), scopes.branchOf(request.baseId()));
        return view(release);
    }

    /** Takes a published state into a release, so it can change there without touching the state it came from. */
    @Transactional
    public UUID forkMapping(UUID releaseId, UUID contractKey, String actor) {
        requireOpen(releaseId);
        if (mappings.findByContractKeyAndScopeKey(contractKey, Scope.of(releaseId)).isPresent()) {
            throw conflict("Dieser Contract ist bereits im Release.");
        }
        var source = scopes.effectiveMappings(releaseId).get(contractKey);
        if (source == null) {
            throw notFound("Dieser Contract ist in diesem Release nicht sichtbar.");
        }
        if (source.getPublishedVersion() == null) {
            throw conflict("Nur veröffentlichte Stände können übernommen werden.");
        }
        var fork = mappings.saveAndFlush(source.forkInto(releaseId, actor));
        events.save(new ReviewEvent(fork.getId(), ReviewEventType.FORKED, actor, "Stand " + source.getPublishedVersion()));
        return fork.getId();
    }

    @Transactional
    public UUID forkProduct(UUID releaseId, UUID productKey, String actor) {
        requireOpen(releaseId);
        if (products.findByProductKeyAndScopeKey(productKey, Scope.of(releaseId)).isPresent()) {
            throw conflict("Dieses Datenprodukt ist bereits im Release.");
        }
        var source = scopes.effectiveProducts(releaseId).get(productKey);
        if (source == null) {
            throw notFound("Dieses Datenprodukt ist in diesem Release nicht sichtbar.");
        }
        if (source.getPublishedVersion() == null) {
            throw conflict("Nur veröffentlichte Stände können übernommen werden.");
        }
        var fork = products.saveAndFlush(source.forkInto(releaseId, actor));
        events.save(new ReviewEvent(fork.getId(), ReviewEventType.FORKED, actor, "Stand " + source.getPublishedVersion()));
        return fork.getId();
    }

    @Transactional
    public ReleaseView close(UUID releaseId, String actor) {
        var release = requireOpen(releaseId);
        var blockers = closeBlockers(release);
        if (!blockers.isEmpty()) {
            throw conflict(String.join(" ", blockers));
        }
        var scope = Scope.of(releaseId);
        var releaseMappings = mappings.findByScopeKey(scope);
        var releaseProducts = products.findByScopeKey(scope);

        var files = new ArrayList<GeneratedArtifact>();
        for (var mapping : releaseMappings) {
            files.addAll(artifacts.generate(mapping));
        }
        for (var product : releaseProducts) {
            files.add(productGenerator.generate(product));
        }
        var author = currentUser.identity(actor);
        var mainBranch = scopes.branchOf(null);
        var commit = git.publish(mainBranch, files, "Release " + release.getName() + "\n\n" + release.getTitle() + "\n", author);
        git.tag("release/" + release.getName(), commit, release.getTitle());

        var previousVersions = new java.util.HashMap<UUID, String>();
        for (var mapping : releaseMappings) {
            mappings.findByContractKeyAndScopeKey(mapping.getKey(), Scope.MAIN).ifPresent(current -> {
                previousVersions.put(mapping.getKey(), current.getPublishedVersion());
                current.archive();
            });
        }
        for (var product : releaseProducts) {
            products.findByProductKeyAndScopeKey(product.getKey(), Scope.MAIN).ifPresent(DataProduct::archive);
        }
        mappings.flush();
        products.flush();
        releaseMappings.forEach(Mapping::moveToMainline);
        releaseProducts.forEach(DataProduct::moveToMainline);
        versions.findByReleaseId(releaseId).forEach(version -> version.moveToMainline());
        mappings.flush();
        products.flush();

        release.markReleased(commit);
        for (var child : releases.findByBaseIdAndState(releaseId, ReleaseState.OPEN)) {
            child.rebaseOn(null);
        }
        releases.flush();

        for (var mapping : releaseMappings) {
            tasks.planContract(mapping, previousVersions.get(mapping.getKey()));
            forwarder.contractPublished(mapping, mapping.getPublishedVersion(), artifacts.generate(mapping), actor);
        }
        for (var product : releaseProducts) {
            tasks.planProduct(product);
            forwarder.productPublished(product, product.getPublishedVersion(), List.of(productGenerator.generate(product)), actor);
        }
        return view(release);
    }

    private List<String> closeBlockers(ReleasePlan release) {
        var blockers = new ArrayList<String>();
        if (release.getBaseId() != null) {
            var base = releases.findById(release.getBaseId()).map(ReleasePlan::getName).orElse("?");
            blockers.add("Zuerst muss das Basisrelease " + base + " abgeschlossen werden.");
        }
        var scope = Scope.of(release.getId());
        for (var mapping : mappings.findByScopeKey(scope)) {
            if (mapping.getStatus() != ReviewStatus.PUBLISHED) {
                blockers.add("Contract " + mapping.getContractName() + " ist noch nicht veröffentlicht.");
            }
        }
        for (var product : products.findByScopeKey(scope)) {
            if (product.getStatus() != ReviewStatus.PUBLISHED) {
                blockers.add("Datenprodukt " + product.getProductName() + " ist noch nicht veröffentlicht.");
            }
        }
        return blockers;
    }

    private ReleasePlan requireOpen(UUID releaseId) {
        var release = scopes.find(releaseId);
        if (release.getState() != ReleaseState.OPEN) {
            throw conflict("Das Release " + release.getName() + " ist bereits abgeschlossen.");
        }
        return release;
    }

    private ReleaseView view(ReleasePlan release) {
        var items = new ArrayList<ReleaseItem>();
        var open = release.getState() == ReleaseState.OPEN;
        if (open) {
            var scope = Scope.of(release.getId());
            for (var mapping : mappings.findByScopeKey(scope)) {
                items.add(new ReleaseItem("CONTRACT", mapping.getId(), mapping.getKey(), mapping.getContractName(),
                        mapping.getContractVersion(), mapping.getStatus().name(),
                        (int) conflicts.countBySubjectIdAndState(mapping.getId(), MergeConflict.State.OPEN)));
            }
            for (var product : products.findByScopeKey(scope)) {
                items.add(new ReleaseItem("PRODUCT", product.getId(), product.getKey(), product.getProductName(),
                        product.getProductVersion(), product.getStatus().name(),
                        (int) conflicts.countBySubjectIdAndState(product.getId(), MergeConflict.State.OPEN)));
            }
        }
        var baseName = release.getBaseId() == null ? null : releases.findById(release.getBaseId()).map(ReleasePlan::getName).orElse(null);
        return new ReleaseView(release.getId(), release.getName(), release.getTitle(), release.getBaseId(), baseName,
                release.getState().name(), release.getBranch(), release.getCreatedBy(), release.getCreatedAt(),
                release.getReleasedAt(), release.getReleasedCommit(), items, open ? closeBlockers(release) : List.of());
    }
}
