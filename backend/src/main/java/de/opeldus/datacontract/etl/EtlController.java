package de.opeldus.datacontract.etl;

import static de.opeldus.datacontract.common.ApiExceptions.badRequest;

import de.opeldus.datacontract.security.CurrentUser;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/etl")
public class EtlController {

    private final EtlService service;
    private final CurrentUser currentUser;

    public EtlController(EtlService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    public record Sequence(String mermaid) {}

    @PostMapping("/imports")
    @PreAuthorize("hasRole('EDITOR')")
    public EtlService.ImportReport importFile(@RequestParam("file") MultipartFile file) {
        try {
            var name = file.getOriginalFilename() == null ? "unbekannt" : file.getOriginalFilename();
            return service.importFile(file.getBytes(), name, currentUser.username());
        } catch (IOException exception) {
            throw badRequest("Die Datei konnte nicht gelesen werden.");
        }
    }

    @GetMapping("/jobs")
    public List<EtlService.JobSummary> jobs() {
        return service.list();
    }

    @GetMapping("/jobs/{id}")
    public EtlService.JobDetail job(@PathVariable UUID id) {
        return service.get(id);
    }

    @DeleteMapping("/jobs/{id}")
    @PreAuthorize("hasRole('EDITOR')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @GetMapping("/graph")
    public EtlGraph graph() {
        return service.graph();
    }

    @GetMapping("/sequence")
    public Sequence sequence(@RequestParam String node) {
        return new Sequence(service.chainSequence(node));
    }
}
