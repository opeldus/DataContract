package de.opeldus.datacontract.integration;

import static de.opeldus.datacontract.common.ApiExceptions.conflict;
import static de.opeldus.datacontract.common.ApiExceptions.notFound;

import de.opeldus.datacontract.mapping.MappingRepository;
import de.opeldus.datacontract.product.DataProductRepository;
import de.opeldus.datacontract.review.ReviewStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class IntegrationController {

    private final RequirementDirectory requirements;
    private final IntegrationTaskRepository tasks;
    private final IntegrationPageRepository pages;
    private final MappingRepository mappings;
    private final DataProductRepository products;
    private final List<DocumentationPublisher> documentation;
    private final String publicUrl;

    IntegrationController(RequirementDirectory requirements, IntegrationTaskRepository tasks,
            IntegrationPageRepository pages, MappingRepository mappings, DataProductRepository products,
            List<DocumentationPublisher> documentation, @Value("${app.public-url:}") String publicUrl) {
        this.requirements = requirements;
        this.tasks = tasks;
        this.pages = pages;
        this.mappings = mappings;
        this.products = products;
        this.documentation = documentation;
        this.publicUrl = publicUrl;
    }

    public record Status(boolean jira, boolean confluence, boolean publicUrl) {}

    public record RequirementSuggestion(String key, String summary) {}

    public record TaskView(UUID id, String kind, String target, String state, int attempts, String lastError,
            Instant nextAttemptAt, Instant updatedAt) {}

    public record SubjectTasks(String pageUrl, List<TaskView> tasks) {}

    public record TracedRule(UUID mappingId, String mappingName, String contractName, String contractVersion,
            ReviewStatus status, String targetColumn) {}

    public record TracedProduct(UUID productId, String productName, String productTitle) {}

    public record TraceEntry(String key, String url, String check, String summary, String status, String statusCategory,
            String type, List<TracedRule> rules, List<TracedProduct> products) {}

    public record Trace(List<TraceEntry> requirements, int rulesWithoutRequirement) {}

    @GetMapping("/integrations/status")
    public Status status() {
        return new Status(requirements.isConfigured(), !documentation.isEmpty(), StringUtils.hasText(publicUrl));
    }

    @GetMapping("/requirements/search")
    public List<RequirementSuggestion> search(@RequestParam(defaultValue = "") String q) {
        return requirements.search(q).stream()
                .map(issue -> new RequirementSuggestion(issue.key(), issue.summary()))
                .toList();
    }

    @GetMapping("/requirements")
    @Transactional(readOnly = true)
    public Trace trace() {
        var rulesByKey = new TreeMap<String, List<TracedRule>>();
        var contractsByKey = new TreeMap<String, List<UUID>>();
        int withoutRequirement = 0;
        for (var mapping : mappings.findAll()) {
            for (var rule : mapping.getRules()) {
                var key = rule.getRequirementKey();
                if (key == null) {
                    withoutRequirement++;
                    continue;
                }
                rulesByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new TracedRule(mapping.getId(),
                        mapping.getName(), mapping.getContractName(), mapping.getContractVersion(), mapping.getStatus(),
                        rule.getTargetColumn()));
                contractsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(mapping.getId());
            }
        }
        var allProducts = products.findAll();
        var entries = new ArrayList<TraceEntry>();
        for (var entry : rulesByKey.entrySet()) {
            var key = entry.getKey();
            var contracts = contractsByKey.get(key);
            var traced = allProducts.stream()
                    .filter(product -> product.getPorts().stream().anyMatch(port -> port.getContract() != null
                            && contracts.contains(port.getContract().getId())))
                    .map(product -> new TracedProduct(product.getId(), product.getProductName(), product.getName()))
                    .toList();
            var issue = requirements.find(key).orElse(null);
            entries.add(new TraceEntry(key, requirements.url(key).orElse(null), requirements.check(key).name(),
                    issue == null ? null : issue.summary(), issue == null ? null : issue.status(),
                    issue == null ? null : issue.statusCategory(), issue == null ? null : issue.type(),
                    entry.getValue(), traced));
        }
        return new Trace(entries, withoutRequirement);
    }

    @GetMapping("/integrations/tasks")
    @Transactional(readOnly = true)
    public SubjectTasks tasks(@RequestParam UUID subjectId) {
        var views = tasks.findBySubjectIdOrderByCreatedAtDesc(subjectId).stream()
                .filter(task -> task.state != IntegrationTask.State.SUPERSEDED)
                .map(task -> new TaskView(task.id, task.kind.name(), task.target, task.state.name(), task.attempts,
                        task.lastError, task.nextAttemptAt, task.updatedAt))
                .toList();
        return new SubjectTasks(pages.findById(subjectId).map(page -> page.url).orElse(null), views);
    }

    @PostMapping("/integrations/tasks/{id}/retry")
    @PreAuthorize("hasRole('REVIEWER')")
    @Transactional
    public void retry(@PathVariable UUID id) {
        var task = tasks.findById(id).orElseThrow(() -> notFound("Aufgabe nicht gefunden."));
        if (task.state != IntegrationTask.State.FAILED) {
            throw conflict("Nur fehlgeschlagene Aufgaben können wiederholt werden.");
        }
        task.retry();
    }
}
