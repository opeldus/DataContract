package de.opeldus.datacontract.review;

import static de.opeldus.datacontract.common.ApiExceptions.badRequest;
import static de.opeldus.datacontract.common.ApiExceptions.notFound;

import de.opeldus.datacontract.artifact.ArtifactService;
import de.opeldus.datacontract.change.ContractSnapshot;
import de.opeldus.datacontract.integration.IntegrationTaskPlanner;
import de.opeldus.datacontract.mapping.PublishedVersion;
import de.opeldus.datacontract.mapping.PublishedVersionRepository;
import de.opeldus.datacontract.mapping.Mapping;
import de.opeldus.datacontract.mapping.MappingDtos.MappingDetail;
import de.opeldus.datacontract.mapping.MappingRepository;
import de.opeldus.datacontract.mapping.MappingService;
import de.opeldus.datacontract.release.ReleaseForwarder;
import de.opeldus.datacontract.validation.MappingValidator;
import de.opeldus.datacontract.validation.ValidationIssue;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {

    private final MappingRepository mappings;
    private final PublishedVersionRepository contractVersions;
    private final MappingService mappingService;
    private final MappingValidator validator;
    private final ArtifactService artifacts;
    private final ReviewWorkflow workflow;
    private final IntegrationTaskPlanner integrationTasks;
    private final ReleaseForwarder forwarder;

    public ReviewService(MappingRepository mappings, PublishedVersionRepository contractVersions,
            MappingService mappingService, MappingValidator validator, ArtifactService artifacts,
            ReviewWorkflow workflow, IntegrationTaskPlanner integrationTasks,
            ReleaseForwarder forwarder) {
        this.mappings = mappings;
        this.contractVersions = contractVersions;
        this.mappingService = mappingService;
        this.validator = validator;
        this.artifacts = artifacts;
        this.workflow = workflow;
        this.integrationTasks = integrationTasks;
        this.forwarder = forwarder;
    }

    @Transactional
    public MappingDetail submit(UUID id, String actor) {
        var mapping = load(id);
        var errors = validator.validate(mapping).stream()
                .filter(issue -> issue.severity() == ValidationIssue.Severity.ERROR)
                .count();
        workflow.submit(mapping, errors, artifacts.contentHash(mapping), actor);
        return saved(mapping);
    }

    @Transactional
    public MappingDetail approve(UUID id, String expectedHash, String actor) {
        var mapping = load(id);
        workflow.approve(mapping, expectedHash, artifacts.contentHash(mapping), actor);
        return saved(mapping);
    }

    @Transactional
    public MappingDetail requestChanges(UUID id, String comment, String actor) {
        var mapping = load(id);
        workflow.requestChanges(mapping, comment, actor);
        return saved(mapping);
    }

    @Transactional
    public MappingDetail comment(UUID id, String comment, UUID ruleId, String actor) {
        var mapping = load(id);
        if (ruleId != null && mapping.getRules().stream().noneMatch(rule -> rule.getId().equals(ruleId))) {
            throw badRequest("Die Regel gehört nicht zu diesem Mapping.");
        }
        workflow.comment(mapping, comment, ruleId, actor);
        return mappingService.detail(mapping);
    }

    @Transactional
    public MappingDetail reopen(UUID id, String actor) {
        var mapping = load(id);
        workflow.reopen(mapping, actor);
        return saved(mapping);
    }

    @Transactional
    public MappingDetail publish(UUID id, String actor) {
        var mapping = load(id);
        var previousVersion = mapping.getPublishedVersion();
        var generated = artifacts.generate(mapping);
        var title = mapping.getContractName() + " " + mapping.getContractVersion();
        var commit = workflow.publish(mapping, generated, artifacts.contentHash(generated), title, actor);
        contractVersions.save(new PublishedVersion(mapping.getKey(), mapping.getContractVersion(), commit,
                ContractSnapshot.of(mapping).toJson(), mapping.getReleaseId()));
        integrationTasks.planContract(mapping, previousVersion);
        forwarder.contractPublished(mapping, mapping.getContractVersion(), generated, actor);
        return saved(mapping);
    }

    @Transactional
    public MappingDetail resolveConflict(UUID id, UUID conflictId, boolean takeTheirs, String actor) {
        var mapping = load(id);
        forwarder.resolve(conflictId, id, takeTheirs, actor);
        return saved(mapping);
    }

    private MappingDetail saved(Mapping mapping) {
        return mappingService.detail(mappings.saveAndFlush(mapping));
    }

    private Mapping load(UUID id) {
        return mappings.findById(id).orElseThrow(() -> notFound("Mapping nicht gefunden."));
    }
}
