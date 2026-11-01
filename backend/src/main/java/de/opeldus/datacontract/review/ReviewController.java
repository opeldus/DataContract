package de.opeldus.datacontract.review;

import de.opeldus.datacontract.mapping.MappingDtos.MappingDetail;
import de.opeldus.datacontract.security.CurrentUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mappings/{id}")
public class ReviewController {

    private final ReviewService service;
    private final CurrentUser currentUser;

    public ReviewController(ReviewService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping("/submit")
    @PreAuthorize("hasRole('EDITOR')")
    public MappingDetail submit(@PathVariable UUID id) {
        return service.submit(id, currentUser.username());
    }

    @PostMapping("/approve")
    @PreAuthorize("hasRole('REVIEWER')")
    public MappingDetail approve(@PathVariable UUID id, @RequestBody ApproveRequest request) {
        return service.approve(id, request.contentHash(), currentUser.username());
    }

    @PostMapping("/request-changes")
    @PreAuthorize("hasRole('REVIEWER')")
    public MappingDetail requestChanges(@PathVariable UUID id, @RequestBody CommentRequest request) {
        return service.requestChanges(id, request.comment(), currentUser.username());
    }

    @PostMapping("/comments")
    public MappingDetail comment(@PathVariable UUID id, @RequestBody CommentRequest request) {
        return service.comment(id, request.comment(), request.ruleId(), currentUser.username());
    }

    @PostMapping("/reopen")
    @PreAuthorize("hasAnyRole('EDITOR', 'REVIEWER')")
    public MappingDetail reopen(@PathVariable UUID id) {
        return service.reopen(id, currentUser.username());
    }

    @PostMapping("/publish")
    @PreAuthorize("hasRole('REVIEWER')")
    public MappingDetail publish(@PathVariable UUID id) {
        return service.publish(id, currentUser.username());
    }

    public record ApproveRequest(String contentHash) {}

    public record CommentRequest(String comment, UUID ruleId) {}
}
