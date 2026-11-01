package de.opeldus.datacontract.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.opeldus.datacontract.change.ContractSnapshot;
import de.opeldus.datacontract.integration.IntegrationTask.Kind;
import de.opeldus.datacontract.integration.IntegrationTask.State;
import de.opeldus.datacontract.mapping.ContractVersion;
import de.opeldus.datacontract.mapping.ContractVersionRepository;
import de.opeldus.datacontract.mapping.Mapping;
import de.opeldus.datacontract.mapping.MappingRule;
import de.opeldus.datacontract.product.DataProduct;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Called inside the publish transaction: tasks exist if and only if the publish commits. The payload holds what was
 * published, so later edits to a draft cannot leak into Jira or Confluence.
 */
@Component
public class IntegrationTaskPlanner {

    private static final Logger log = LoggerFactory.getLogger(IntegrationTaskPlanner.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<State> OPEN_STATES = List.of(State.PENDING, State.FAILED);

    private final IntegrationTaskRepository tasks;
    private final ContractVersionRepository versions;
    private final ConfluenceRenderer renderer;
    private final List<IssueTracker> trackers;
    private final List<DocumentationPublisher> documentation;
    private final String publicUrl;

    IntegrationTaskPlanner(IntegrationTaskRepository tasks, ContractVersionRepository versions,
            ConfluenceRenderer renderer, List<IssueTracker> trackers, List<DocumentationPublisher> documentation,
            @Value("${app.public-url:}") String publicUrl) {
        this.tasks = tasks;
        this.versions = versions;
        this.renderer = renderer;
        this.trackers = trackers;
        this.documentation = documentation;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    record LinkPayload(String globalId, String url, String title, String summary) {}

    record PagePayload(String title, String body, String marker, String versionMessage) {}

    public void planContract(Mapping mapping, String previousVersion) {
        var appUrl = appUrl("mappings", mapping.getId());
        if (!documentation.isEmpty()) {
            planPage(mapping.getId(), new PagePayload(renderer.contractTitle(mapping), renderer.contract(mapping, appUrl),
                    ConfluenceRenderer.marker(mapping.getId()), "Version " + mapping.getContractVersion()));
        }
        if (trackers.isEmpty()) {
            return;
        }
        if (appUrl == null) {
            log.warn("app.public-url is not set, so no links are created in Jira");
            return;
        }
        var globalId = "datacontract:mapping:" + mapping.getId();
        var current = currentKeys(mapping);
        var link = new LinkPayload(globalId, appUrl, "Contract " + mapping.getContractName() + " " + mapping.getContractVersion(),
                mapping.getName());
        for (var key : current) {
            supersedeOpen(mapping.getId(), Kind.ISSUE_LINK, key);
            supersedeOpen(mapping.getId(), Kind.ISSUE_UNLINK, key);
            tasks.save(new IntegrationTask(mapping.getId(), Kind.ISSUE_LINK, key, write(link)));
        }
        for (var key : previousKeys(mapping.getId(), previousVersion)) {
            if (!current.contains(key)) {
                supersedeOpen(mapping.getId(), Kind.ISSUE_LINK, key);
                supersedeOpen(mapping.getId(), Kind.ISSUE_UNLINK, key);
                tasks.save(new IntegrationTask(mapping.getId(), Kind.ISSUE_UNLINK, key, write(link)));
            }
        }
    }

    public void planProduct(DataProduct product) {
        if (!documentation.isEmpty()) {
            planPage(product.getId(), new PagePayload(renderer.productTitle(product),
                    renderer.product(product, appUrl("products", product.getId())),
                    ConfluenceRenderer.marker(product.getId()), "Version " + product.getProductVersion()));
        }
    }

    private void planPage(UUID subjectId, PagePayload page) {
        supersedeOpen(subjectId, Kind.DOCUMENTATION_PAGE, null);
        tasks.save(new IntegrationTask(subjectId, Kind.DOCUMENTATION_PAGE, null, write(page)));
    }

    /** A retry of an older task must never overwrite a newer publish. */
    private void supersedeOpen(UUID subjectId, Kind kind, String target) {
        tasks.findBySubjectIdAndKindAndStateIn(subjectId, kind, OPEN_STATES).stream()
                .filter(task -> Objects.equals(task.target, target))
                .forEach(IntegrationTask::supersede);
    }

    private static Set<String> currentKeys(Mapping mapping) {
        return mapping.getRules().stream()
                .map(MappingRule::getRequirementKey)
                .filter(RequirementDirectory::isValidKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> previousKeys(UUID mappingId, String previousVersion) {
        if (previousVersion == null) {
            return Set.of();
        }
        return versions.findById(new ContractVersion.Key(mappingId, previousVersion))
                .map(ContractVersion::getSnapshot)
                .filter(Objects::nonNull)
                .map(ContractSnapshot::fromJson)
                .map(snapshot -> snapshot.rules().stream()
                        .map(ContractSnapshot.Rule::requirementKey)
                        .filter(RequirementDirectory::isValidKey)
                        .collect(Collectors.toCollection(LinkedHashSet::new)))
                .map(keys -> (Set<String>) keys)
                .orElse(Set.of());
    }

    private String appUrl(String section, UUID id) {
        return StringUtils.hasText(publicUrl) ? publicUrl + "/#/" + section + "/" + id : null;
    }

    private static String write(Object payload) {
        try {
            return JSON.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
