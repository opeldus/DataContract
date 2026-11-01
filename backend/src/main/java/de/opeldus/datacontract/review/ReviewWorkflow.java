package de.opeldus.datacontract.review;

import static de.opeldus.datacontract.common.ApiExceptions.badRequest;
import static de.opeldus.datacontract.common.ApiExceptions.conflict;
import static de.opeldus.datacontract.common.ApiExceptions.forbidden;

import de.opeldus.datacontract.artifact.GeneratedArtifact;
import de.opeldus.datacontract.publish.GitPublisher;
import de.opeldus.datacontract.release.ReleaseScopes;
import de.opeldus.datacontract.security.CurrentUser;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The state machine shared by everything that is reviewed; callers load, validate and save the subject. */
@Component
public class ReviewWorkflow {

    private static final Set<ReviewEventType> AUTHORING_EVENTS = Set.of(ReviewEventType.CREATED,
            ReviewEventType.EDITED, ReviewEventType.IMPORTED, ReviewEventType.SUBMITTED, ReviewEventType.FORKED);

    private final ReviewEventRepository events;
    private final GitPublisher publisher;
    private final CurrentUser currentUser;
    private final ReleaseScopes scopes;

    public ReviewWorkflow(ReviewEventRepository events, GitPublisher publisher, CurrentUser currentUser,
            ReleaseScopes scopes) {
        this.events = events;
        this.publisher = publisher;
        this.currentUser = currentUser;
        this.scopes = scopes;
    }

    public void submit(ReviewSubject subject, long errorCount, String contentHash, String actor) {
        if (!subject.getStatus().isEditable()) {
            throw conflict("Nur Entwürfe können eingereicht werden.");
        }
        if (errorCount > 0) {
            throw conflict("Es sind noch " + errorCount + " Fehler offen.");
        }
        subject.setStatus(ReviewStatus.IN_REVIEW);
        record(subject, new ReviewEvent(subject.getId(), ReviewEventType.SUBMITTED, actor)
                .withContentHash(contentHash));
    }

    public void approve(ReviewSubject subject, String expectedHash, String contentHash, String actor) {
        requireStatus(subject, ReviewStatus.IN_REVIEW);
        if (authorsSinceLastPublish(subject.getId()).contains(actor)) {
            throw forbidden("Vier-Augen-Prinzip: Wer daran mitgearbeitet hat, kann es nicht freigeben.");
        }
        if (!contentHash.equals(expectedHash)) {
            throw conflict("Der Inhalt hat sich seit dem Öffnen geändert. Bitte neu laden und erneut prüfen.");
        }
        subject.setStatus(ReviewStatus.APPROVED);
        record(subject, new ReviewEvent(subject.getId(), ReviewEventType.APPROVED, actor)
                .withContentHash(contentHash));
    }

    public void requestChanges(ReviewSubject subject, String comment, String actor) {
        requireStatus(subject, ReviewStatus.IN_REVIEW);
        requireComment(comment);
        subject.setStatus(ReviewStatus.CHANGES_REQUESTED);
        record(subject, new ReviewEvent(subject.getId(), ReviewEventType.CHANGES_REQUESTED, actor, comment.trim()));
    }

    public void comment(ReviewSubject subject, String comment, UUID ruleId, String actor) {
        requireComment(comment);
        events.save(new ReviewEvent(subject.getId(), ReviewEventType.COMMENTED, actor, comment.trim())
                .withRuleId(ruleId));
    }

    /** Going back to draft discards any approval, because the next review starts from the new content. */
    public void reopen(ReviewSubject subject, String actor) {
        if (subject.getStatus().isEditable()) {
            throw conflict("Das ist bereits in Bearbeitung.");
        }
        if (subject.isArchived()) {
            throw conflict("Dieser Stand wurde durch ein Release ersetzt und kann nicht mehr bearbeitet werden.");
        }
        subject.setStatus(ReviewStatus.DRAFT);
        record(subject, new ReviewEvent(subject.getId(), ReviewEventType.REOPENED, actor));
    }

    /** Commits exactly what was approved; a failure leaves the subject approved so publishing can be retried. */
    public String publish(ReviewSubject subject, List<GeneratedArtifact> artifacts, String contentHash, String title,
            String actor) {
        requireStatus(subject, ReviewStatus.APPROVED);
        var history = events.findBySubjectIdOrderByCreatedAtAsc(subject.getId());
        var approval = last(history, Set.of(ReviewEventType.APPROVED));
        if (approval == null) {
            throw conflict("Es liegt keine Freigabe vor.");
        }
        if (!contentHash.equals(approval.getContentHash())) {
            throw conflict("Der Inhalt entspricht nicht mehr der Freigabe. Bitte erneut prüfen lassen.");
        }
        var lastAuthor = last(history, AUTHORING_EVENTS);
        var author = currentUser.identity(lastAuthor != null ? lastAuthor.getActor() : subject.getCreatedBy());
        var message = "Publish %s%n%nSubject: %s%nApproved-by: %s%nPublished-by: %s%nContent-SHA256: %s%n".formatted(
                title, subject.getId(), approval.getActor(), actor, contentHash);
        var commit = publisher.publish(scopes.branchOf(subject.getReleaseId()), artifacts, message, author);
        subject.markPublished(commit);
        record(subject, new ReviewEvent(subject.getId(), ReviewEventType.PUBLISHED, actor, commit)
                .withContentHash(contentHash));
        return commit;
    }

    private void record(ReviewSubject subject, ReviewEvent event) {
        subject.touch();
        events.save(event);
    }

    private Set<String> authorsSinceLastPublish(UUID id) {
        var authors = new HashSet<String>();
        for (var event : events.findBySubjectIdOrderByCreatedAtAsc(id)) {
            if (event.getType() == ReviewEventType.PUBLISHED) {
                authors.clear();
            } else if (AUTHORING_EVENTS.contains(event.getType())) {
                authors.add(event.getActor());
            }
        }
        return authors;
    }

    private static ReviewEvent last(List<ReviewEvent> history, Set<ReviewEventType> types) {
        return history.stream()
                .filter(event -> types.contains(event.getType()))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private static void requireStatus(ReviewSubject subject, ReviewStatus expected) {
        if (subject.getStatus() != expected) {
            throw conflict("Diese Aktion ist im Status " + subject.getStatus() + " nicht möglich.");
        }
    }

    private static void requireComment(String comment) {
        if (comment == null || comment.isBlank()) {
            throw badRequest("Ein Kommentar ist erforderlich.");
        }
        if (comment.length() > 4000) {
            throw badRequest("Der Kommentar darf höchstens 4000 Zeichen lang sein.");
        }
    }
}
