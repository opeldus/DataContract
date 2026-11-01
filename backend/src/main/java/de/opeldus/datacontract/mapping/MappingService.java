package de.opeldus.datacontract.mapping;

import static de.opeldus.datacontract.common.ApiExceptions.conflict;
import static de.opeldus.datacontract.common.ApiExceptions.notFound;

import de.opeldus.datacontract.artifact.ArtifactService;
import de.opeldus.datacontract.change.ChangeReport;
import de.opeldus.datacontract.change.ContractChangeAnalyzer;
import de.opeldus.datacontract.imports.ExcelMappingImporter;
import de.opeldus.datacontract.mapping.MappingDtos.CreateMappingRequest;
import de.opeldus.datacontract.mapping.MappingDtos.EventDto;
import de.opeldus.datacontract.mapping.MappingDtos.ImportResult;
import de.opeldus.datacontract.mapping.MappingDtos.MappingDetail;
import de.opeldus.datacontract.mapping.MappingDtos.MappingSummary;
import de.opeldus.datacontract.mapping.MappingDtos.PreviewFile;
import de.opeldus.datacontract.mapping.MappingDtos.RuleDto;
import de.opeldus.datacontract.mapping.MappingDtos.UpdateMappingRequest;
import de.opeldus.datacontract.release.MergeConflict;
import de.opeldus.datacontract.release.MergeConflictRepository;
import de.opeldus.datacontract.release.ReleaseDtos.ConflictView;
import de.opeldus.datacontract.release.ReleaseScopes;
import de.opeldus.datacontract.release.ReleaseState;
import de.opeldus.datacontract.review.ReviewEvent;
import de.opeldus.datacontract.review.ReviewEventRepository;
import de.opeldus.datacontract.review.ReviewEventType;
import de.opeldus.datacontract.validation.MappingValidator;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MappingService {

    private final MappingRepository mappings;
    private final ReviewEventRepository events;
    private final MappingValidator validator;
    private final ArtifactService artifacts;
    private final ExcelMappingImporter excelImporter;
    private final ContractChangeAnalyzer changes;
    private final ReleaseScopes scopes;
    private final MergeConflictRepository conflicts;

    public MappingService(MappingRepository mappings, ReviewEventRepository events, MappingValidator validator,
            ArtifactService artifacts, ExcelMappingImporter excelImporter, ContractChangeAnalyzer changes,
            ReleaseScopes scopes, MergeConflictRepository conflicts) {
        this.mappings = mappings;
        this.events = events;
        this.validator = validator;
        this.artifacts = artifacts;
        this.excelImporter = excelImporter;
        this.changes = changes;
        this.scopes = scopes;
        this.conflicts = conflicts;
    }

    @Transactional(readOnly = true)
    public List<MappingSummary> list(UUID releaseId) {
        return scopes.effectiveMappings(releaseId).values().stream()
                .sorted(Comparator.comparing(Mapping::getUpdatedAt).reversed())
                .map(mapping -> new MappingSummary(mapping.getId(), mapping.getKey(), mapping.getName(),
                        mapping.getContractName(), mapping.getContractVersion(), mapping.getStatus(),
                        mapping.getOwnerTeam(), mapping.getRules().size(), mapping.getUpdatedAt(),
                        !Objects.equals(mapping.getReleaseId(), releaseId),
                        scopes.releaseName(mapping.getReleaseId()).orElse(null)))
                .toList();
    }

    @Transactional
    public MappingDetail create(CreateMappingRequest request, String actor) {
        if (mappings.existsByContractName(request.contractName())) {
            throw conflict("Der Contract-Name '" + request.contractName() + "' ist bereits vergeben.");
        }
        if (request.releaseId() != null && scopes.find(request.releaseId()).getState() != ReleaseState.OPEN) {
            throw conflict("Dieses Release ist bereits abgeschlossen.");
        }
        var mapping = mappings.saveAndFlush(new Mapping(request.name(), request.contractName(), actor, request.releaseId()));
        events.save(new ReviewEvent(mapping.getId(), ReviewEventType.CREATED, actor));
        return detail(mapping);
    }

    @Transactional(readOnly = true)
    public MappingDetail get(UUID id) {
        return detail(load(id));
    }

    @Transactional
    public MappingDetail update(UUID id, UpdateMappingRequest request, String actor) {
        var mapping = load(id);
        requireEditable(mapping);
        if (mapping.getVersion() != request.version()) {
            throw conflict("Das Mapping wurde zwischenzeitlich von jemand anderem geändert. Bitte neu laden.");
        }
        mapping.setName(request.name());
        mapping.setContractVersion(request.contractVersion().trim());
        mapping.setDomain(blankToNull(request.domain()));
        mapping.setDescription(blankToNull(request.description()));
        mapping.setTargetTable(blankToNull(request.targetTable()));
        mapping.setOwnerTeam(blankToNull(request.ownerTeam()));
        replaceRules(mapping, request.rules() == null ? List.of() : request.rules());
        mapping.touch();
        events.save(new ReviewEvent(id, ReviewEventType.EDITED, actor));
        return detail(mappings.saveAndFlush(mapping));
    }

    @Transactional
    public ImportResult importExcel(UUID id, InputStream workbook, String actor) {
        var mapping = load(id);
        requireEditable(mapping);
        var parsed = excelImporter.parse(workbook);
        var combined = new ArrayList<>(mapping.getRules().stream().map(RuleDto::from).toList());
        combined.addAll(parsed.rules());
        replaceRules(mapping, combined);
        mapping.touch();
        events.save(new ReviewEvent(id, ReviewEventType.IMPORTED, actor,
                parsed.rules().size() + " Regeln aus Excel importiert"));
        var saved = mappings.saveAndFlush(mapping);
        return new ImportResult(parsed.rules().size(), parsed.skippedRows(), parsed.ignoredColumns(), detail(saved));
    }

    @Transactional(readOnly = true)
    public List<PreviewFile> preview(UUID id) {
        return artifacts.generate(load(id)).stream()
                .map(file -> new PreviewFile(file.path(), file.content()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ChangeReport changes(UUID id) {
        return changes.analyze(load(id));
    }

    public MappingDetail detail(Mapping mapping) {
        var history = events.findBySubjectIdOrderByCreatedAtAsc(mapping.getId()).stream()
                .map(EventDto::from)
                .toList();
        var open = conflicts.findBySubjectIdAndStateOrderByCreatedAtAsc(mapping.getId(), MergeConflict.State.OPEN).stream()
                .map(ConflictView::from)
                .toList();
        return new MappingDetail(mapping.getId(), mapping.getKey(), mapping.getReleaseId(),
                scopes.releaseName(mapping.getReleaseId()).orElse(null), mapping.isArchived(), mapping.getName(), mapping.getContractName(),
                mapping.getContractVersion(), mapping.getDomain(), mapping.getDescription(), mapping.getTargetTable(),
                mapping.getOwnerTeam(), mapping.getStatus(), mapping.getPublishedVersion(),
                mapping.getPublishedCommit(), mapping.getCreatedBy(), mapping.getUpdatedAt(), mapping.getVersion(),
                mapping.getRules().stream().map(RuleDto::from).toList(), validator.validate(mapping), history,
                artifacts.contentHash(mapping), open);
    }

    private Mapping load(UUID id) {
        return mappings.findById(id).orElseThrow(() -> notFound("Mapping nicht gefunden."));
    }

    private void requireEditable(Mapping mapping) {
        if (!mapping.getStatus().isEditable()) {
            throw conflict("Im Status " + mapping.getStatus() + " kann das Mapping nicht bearbeitet werden.");
        }
    }

    private void replaceRules(Mapping mapping, List<RuleDto> rows) {
        var existing = mapping.getRules().stream()
                .collect(Collectors.toMap(MappingRule::getId, Function.identity()));
        var updated = new ArrayList<MappingRule>(rows.size());
        for (var row : rows) {
            var rule = row.id() != null && existing.containsKey(row.id())
                    ? existing.get(row.id())
                    : new MappingRule(mapping);
            rule.setSortOrder(updated.size());
            rule.setSourceDataset(blankToNull(row.sourceDataset()));
            rule.setSourceColumn(blankToNull(row.sourceColumn()));
            rule.setTargetColumn(blankToNull(row.targetColumn()));
            rule.setTargetType(blankToNull(row.targetType()));
            rule.setRequired(row.required());
            rule.setPrimaryKey(row.primaryKey());
            rule.setTransformation(blankToNull(row.transformation()));
            rule.setDescription(blankToNull(row.description()));
            rule.setRequirementKey(blankToNull(row.requirementKey()));
            updated.add(rule);
        }
        mapping.getRules().clear();
        mapping.getRules().addAll(updated);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
