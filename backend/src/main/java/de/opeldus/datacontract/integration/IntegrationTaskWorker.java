package de.opeldus.datacontract.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.opeldus.datacontract.integration.IntegrationTaskPlanner.ForwardPayload;
import de.opeldus.datacontract.integration.IntegrationTaskPlanner.LinkPayload;
import de.opeldus.datacontract.integration.IntegrationTaskPlanner.PagePayload;
import de.opeldus.datacontract.publish.GitPublisher;
import de.opeldus.datacontract.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;

/** Sends due tasks one by one, outside of any transaction, because Atlassian calls can take seconds. */
@Component
public class IntegrationTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(IntegrationTaskWorker.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IntegrationTaskRepository tasks;
    private final IntegrationPageRepository pages;
    private final List<IssueTracker> trackers;
    private final List<DocumentationPublisher> documentation;
    private final TransactionTemplate transaction;
    private final GitPublisher git;
    private final CurrentUser currentUser;

    IntegrationTaskWorker(IntegrationTaskRepository tasks, IntegrationPageRepository pages, List<IssueTracker> trackers,
            List<DocumentationPublisher> documentation, TransactionTemplate transaction, GitPublisher git,
            CurrentUser currentUser) {
        this.tasks = tasks;
        this.pages = pages;
        this.trackers = trackers;
        this.documentation = documentation;
        this.transaction = transaction;
        this.git = git;
        this.currentUser = currentUser;
    }

    @Scheduled(fixedDelayString = "${app.atlassian.task-interval}")
    public synchronized void runDue() {
        List<IntegrationTask> due;
        do {
            due = tasks.findTop50ByStateAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(IntegrationTask.State.PENDING,
                    Instant.now());
            due.forEach(this::process);
        } while (due.size() == 50);
    }

    /** A failed task moves its next attempt into the future, so it does not come up again in this run. */
    private void process(IntegrationTask task) {
        try {
            send(task);
            task.succeeded();
        } catch (JsonProcessingException | RuntimeException exception) {
            var message = task.kind == IntegrationTask.Kind.GIT_FORWARD
                    ? "Der Branch " + task.target + " konnte nicht aktualisiert werden."
                    : describe(exception);
            log.warn("Integration task {} failed: {}", task.id, message);
            task.failed(message);
        }
        transaction.executeWithoutResult(status -> tasks.save(task));
    }

    private void send(IntegrationTask task) throws JsonProcessingException {
        switch (task.kind) {
            case ISSUE_LINK -> {
                var link = JSON.readValue(task.payload, LinkPayload.class);
                tracker().upsertLink(task.target,
                        new IssueTracker.RemoteLink(link.globalId(), link.url(), link.title(), link.summary()));
            }
            case ISSUE_UNLINK -> tracker().removeLink(task.target, JSON.readValue(task.payload, LinkPayload.class).globalId());
            case DOCUMENTATION_PAGE -> sendPage(task);
            case GIT_FORWARD -> {
                var forward = JSON.readValue(task.payload, ForwardPayload.class);
                git.publish(forward.branch(), forward.files(), forward.message(), currentUser.identity(forward.actor()));
            }
        }
    }

    private void sendPage(IntegrationTask task) throws JsonProcessingException {
        if (documentation.isEmpty()) {
            throw new IntegrationException("Confluence ist nicht mehr konfiguriert.");
        }
        var page = JSON.readValue(task.payload, PagePayload.class);
        var known = pages.findById(task.subjectId).map(stored -> stored.pageId).orElse(null);
        var published = documentation.get(0).upsert(known, page.title(), page.body(), page.marker(), page.versionMessage());
        transaction.executeWithoutResult(status -> {
            var stored = pages.findById(task.subjectId).orElseGet(() -> new IntegrationPage(task.subjectId));
            stored.pageId = published.id();
            stored.url = published.url();
            stored.updatedAt = Instant.now();
            pages.save(stored);
        });
    }

    private IssueTracker tracker() {
        if (trackers.isEmpty()) {
            throw new IntegrationException("Jira ist nicht mehr konfiguriert.");
        }
        return trackers.get(0);
    }

    /** Never contains response bodies or headers, because these messages are shown in the UI. */
    private static String describe(Exception exception) {
        if (exception instanceof IntegrationException) {
            return exception.getMessage();
        }
        if (exception instanceof RestClientResponseException response) {
            var status = response.getStatusCode().value();
            return switch (status) {
                case 401 -> "Atlassian lehnt den Zugang ab (401). Bitte API-Token prüfen.";
                case 403 -> "Dem Zugang fehlen die Rechte (403).";
                case 404 -> "Das Ziel wurde nicht gefunden (404).";
                case 409 -> "Atlassian meldet einen Konflikt (409), zum Beispiel eine nötige Inhaltsfreigabe.";
                case 429 -> "Atlassian begrenzt gerade die Anfragen (429).";
                default -> "Atlassian antwortete mit Status " + status + ".";
            };
        }
        return "Atlassian ist nicht erreichbar.";
    }
}
