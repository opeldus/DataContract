package de.opeldus.datacontract.release;

import de.opeldus.datacontract.release.ReleaseDtos.CreateReleaseRequest;
import de.opeldus.datacontract.release.ReleaseDtos.ReleaseView;
import de.opeldus.datacontract.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseService service;
    private final CurrentUser currentUser;

    public ReleaseController(ReleaseService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ReleaseView> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EDITOR')")
    public ReleaseView create(@Valid @RequestBody CreateReleaseRequest request) {
        return service.create(request, currentUser.username());
    }

    @PostMapping("/{id}/mappings/{key}/fork")
    @PreAuthorize("hasRole('EDITOR')")
    public Map<String, UUID> forkMapping(@PathVariable UUID id, @PathVariable UUID key) {
        return Map.of("id", service.forkMapping(id, key, currentUser.username()));
    }

    @PostMapping("/{id}/products/{key}/fork")
    @PreAuthorize("hasRole('EDITOR')")
    public Map<String, UUID> forkProduct(@PathVariable UUID id, @PathVariable UUID key) {
        return Map.of("id", service.forkProduct(id, key, currentUser.username()));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('REVIEWER')")
    public ReleaseView close(@PathVariable UUID id) {
        return service.close(id, currentUser.username());
    }
}
