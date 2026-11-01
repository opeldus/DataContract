package de.opeldus.datacontract.mapping;

import static de.opeldus.datacontract.common.ApiExceptions.badRequest;

import de.opeldus.datacontract.change.ChangeReport;
import de.opeldus.datacontract.integration.RequirementDirectory;
import de.opeldus.datacontract.mapping.MappingDtos.CreateMappingRequest;
import de.opeldus.datacontract.mapping.MappingDtos.ImportResult;
import de.opeldus.datacontract.mapping.MappingDtos.MappingDetail;
import de.opeldus.datacontract.mapping.MappingDtos.MappingSummary;
import de.opeldus.datacontract.mapping.MappingDtos.PreviewFile;
import de.opeldus.datacontract.mapping.MappingDtos.RuleDto;
import de.opeldus.datacontract.mapping.MappingDtos.UpdateMappingRequest;
import de.opeldus.datacontract.security.CurrentUser;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/mappings")
public class MappingController {

    private final MappingService service;
    private final CurrentUser currentUser;
    private final RequirementDirectory requirements;

    public MappingController(MappingService service, CurrentUser currentUser, RequirementDirectory requirements) {
        this.service = service;
        this.currentUser = currentUser;
        this.requirements = requirements;
    }

    @GetMapping
    public List<MappingSummary> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('EDITOR')")
    public MappingDetail create(@Valid @RequestBody CreateMappingRequest request) {
        return service.create(request, currentUser.username());
    }

    @GetMapping("/{id}")
    public MappingDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('EDITOR')")
    public MappingDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateMappingRequest request) {
        if (request.rules() != null) {
            requirements.resolveUnknown(request.rules().stream().map(RuleDto::requirementKey).toList());
        }
        return service.update(id, request, currentUser.username());
    }

    @PostMapping("/{id}/import")
    @PreAuthorize("hasRole('EDITOR')")
    public ImportResult importExcel(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        try (var input = file.getInputStream()) {
            var result = service.importExcel(id, input, currentUser.username());
            requirements.resolveUnknown(result.mapping().rules().stream().map(RuleDto::requirementKey).toList());
            return new ImportResult(result.imported(), result.skippedRows(), result.ignoredColumns(), service.get(id));
        } catch (IOException exception) {
            throw badRequest("Die Datei konnte nicht gelesen werden.");
        }
    }

    @GetMapping("/{id}/changes")
    public ChangeReport changes(@PathVariable UUID id) {
        return service.changes(id);
    }

    @GetMapping("/{id}/preview")
    public List<PreviewFile> preview(@PathVariable UUID id) {
        return service.preview(id);
    }
}
