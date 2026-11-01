package de.opeldus.datacontract.review;

import static de.opeldus.datacontract.common.ApiExceptions.badRequest;
import static de.opeldus.datacontract.common.ApiExceptions.conflict;
import static de.opeldus.datacontract.common.ApiExceptions.forbidden;
import static de.opeldus.datacontract.common.ApiExceptions.notFound;

import de.opeldus.datacontract.artifact.ArtifactService;
import de.opeldus.datacontract.mapping.Mapping;
import de.opeldus.datacontract.mapping.MappingDtos.MappingDetail;
import de.opeldus.datacontract.mapping.MappingRepository;
import de.opeldus.datacontract.mapping.MappingService;
import de.opeldus.datacontract.mapping.MappingStatus;
import de.opeldus.datacontract.publish.GitPublisher;
import de.opeldus.datacontract.security.CurrentUser;
import de.opeldus.datacontract.validation.MappingValidator;
import de.opeldus.datacontract.validation.ValidationIssue;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {

    private static final Set<ReviewEventType> AUTHORING_EVENTS = Set.of(ReviewEventType.CREATED,
            ReviewEventType.EDITED, ReviewEventType.IMPORTED, ReviewEventType.SUBMITTED);

    private final MappingRepository mappings;
    private final ReviewEventRepository events;
    private final MappingService mappingService;
    private final MappingValidator validator;
    private final ArtifactService artifacts;
    private final GitPublisher publisher;
    private final CurrentUser currentUser;

    public ReviewService(MappingRepository mappings, ReviewEventRepository events, MappingService mappingService,
            MappingValidator validator, ArtifactService artifacts, GitPublisher publisher, CurrentUser currentUser) {
        this.mappings = mappings;
        this.events = events;
        this.mappingService = mappingService;
        this.validator = validator;
        this.artifacts = artifacts;
        this.publisher = publisher;
        this.currentUser = currentUser;
    }

    @Transactional
    public MappingDetail submit(UUID id, String actor) {
        var mapping = load(id);
        if (!mapping.getStatus().isEditable()) {
            throw conflict("Nur Entwürfe können eingereicht werden.");
        }
        var errors = validator.validate(mapping).stream()
                .filter(issue -> issue.severity() == ValidationIssue.Severity.ERROR)
                .count();
        if (errors > 0) {
            throw conflict("Das Mapping enthält noch " + errors + " Fehler.");
        }
        mapping.setStatus(MappingStatus.IN_REVIEW);
        return transition(mapping, new ReviewEvent(id, ReviewEventType.SUBMITTED, actor)
                .withContentHash(artifacts.contentHash(mapping)));
    }

    @Transactional
    public MappingDetail approve(UUID id, String expectedHash, String actor) {
        var mapping = load(id);
        requireStatus(mapping, MappingStatus.IN_REVIEW);
        if (authorsSinceLastPublish(id).contains(actor)) {
            throw forbidden("Vier-Augen-Prinzip: Wer am Mapping mitgearbeitet hat, kann es nicht freigeben.");
        }
        var hash = artifacts.contentHash(mapping);
        if (!hash.equals(expectedHash)) {
            throw conflict("Der Inhalt hat sich seit dem Öffnen geändert. Bitte neu laden und erneut prüfen.");
        }
        mapping.setStatus(MappingStatus.APPROVED);
        return transition(mapping, new ReviewEvent(id, ReviewEventType.APPROVED, actor).withContentHash(hash));
    }

    @Transactional
    public MappingDetail requestChanges(UUID id, String comment, String actor) {
        var mapping = load(id);
        requireStatus(mapping, MappingStatus.IN_REVIEW);
        requireComment(comment);
        mapping.setStatus(MappingStatus.CHANGES_REQUESTED);
        return transition(mapping, new ReviewEvent(id, ReviewEventType.CHANGES_REQUESTED, actor, comment.trim()));
    }

    @Transactional
    public MappingDetail comment(UUID id, String comment, UUID ruleId, String actor) {
        var mapping = load(id);
        requireComment(comment);
        if (ruleId != null && mapping.getRules().stream().noneMatch(rule -> rule.getId().equals(ruleId))) {
            throw badRequest("Die Regel gehört nicht zu diesem Mapping.");
        }
        events.save(new ReviewEvent(id, ReviewEventType.COMMENTED, actor, comment.trim()).withRuleId(ruleId));
        return mappingService.detail(mapping);
    }

    /** Going back to draft discards any approval, because the next review starts from the new content. */
    @Transactional
    public MappingDetail reopen(UUID id, String actor) {
        var mapping = load(id);
        if (mapping.getStatus() == MappingStatus.DRAFT || mapping.getStatus() == MappingStatus.CHANGES_REQUESTED) {
            throw conflict("Das Mapping ist bereits in Bearbeitung.");
        }
        mapping.setStatus(MappingStatus.DRAFT);
        return transition(mapping, new ReviewEvent(id, ReviewEventType.REOPENED, actor));
    }

    @Transactional
    public MappingDetail publish(UUID id, String actor) {
        var mapping = load(id);
        requireStatus(mapping, MappingStatus.APPROVED);
        var hash = artifacts.contentHash(mapping);
        var approval = events.findByMappingIdOrderByCreatedAtAsc(id).stream()
                .filter(event -> event.getType() == ReviewEventType.APPROVED)
                .reduce((first, second) -> second)
                .orElseThrow(() -> conflict("Es liegt keine Freigabe vor."));
        if (!hash.equals(approval.getContentHash())) {
            throw conflict("Der Inhalt entspricht nicht mehr der Freigabe. Bitte erneut prüfen lassen.");
        }
        var author = currentUser.identity(mapping.getCreatedBy());
        var lastAuthor = lastAuthor(id);
        if (lastAuthor != null) {
            author = currentUser.identity(lastAuthor);
        }
        var message = "Publish %s %s%n%nMapping: %s%nApproved-by: %s%nPublished-by: %s%nContent-SHA256: %s%n".formatted(
                mapping.getContractName(), mapping.getContractVersion(), mapping.getId(), approval.getActor(), actor,
                hash);
        var commit = publisher.publish(artifacts.generate(mapping), message, author);
        mapping.markPublished(commit);
        return transition(mapping, new ReviewEvent(id, ReviewEventType.PUBLISHED, actor, commit).withContentHash(hash));
    }

    private MappingDetail transition(Mapping mapping, ReviewEvent event) {
        mapping.touch();
        events.save(event);
        return mappingService.detail(mappings.saveAndFlush(mapping));
    }

    private Set<String> authorsSinceLastPublish(UUID id) {
        var authors = new HashSet<String>();
        for (var event : events.findByMappingIdOrderByCreatedAtAsc(id)) {
            if (event.getType() == ReviewEventType.PUBLISHED) {
                authors.clear();
            } else if (AUTHORING_EVENTS.contains(event.getType())) {
                authors.add(event.getActor());
            }
        }
        return authors;
    }

    private String lastAuthor(UUID id) {
        return events.findByMappingIdOrderByCreatedAtAsc(id).stream()
                .filter(event -> AUTHORING_EVENTS.contains(event.getType()))
                .map(ReviewEvent::getActor)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private Mapping load(UUID id) {
        return mappings.findById(id).orElseThrow(() -> notFound("Mapping nicht gefunden."));
    }

    private static void requireStatus(Mapping mapping, MappingStatus expected) {
        if (mapping.getStatus() != expected) {
            throw conflict("Diese Aktion ist im Status " + mapping.getStatus() + " nicht möglich.");
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
