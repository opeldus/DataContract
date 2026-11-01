package de.opeldus.datacontract.mapping;

import de.opeldus.datacontract.release.ReleaseDtos.ConflictView;
import de.opeldus.datacontract.review.ReviewEvent;
import de.opeldus.datacontract.review.ReviewEventType;
import de.opeldus.datacontract.review.ReviewStatus;
import de.opeldus.datacontract.validation.ValidationIssue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MappingDtos {

    private MappingDtos() {}

    public record CreateMappingRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,99}",
                    message = "nur Kleinbuchstaben, Ziffern, _ und - erlaubt") String contractName,
            UUID releaseId) {}

    public record UpdateMappingRequest(
            long version,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 50) String contractVersion,
            @Size(max = 100) String domain,
            @Size(max = 4000) String description,
            @Size(max = 200) String targetTable,
            @Size(max = 100) String ownerTeam,
            @Valid List<RuleDto> rules) {}

    public record RuleDto(
            UUID id,
            @Size(max = 300) String sourceDataset,
            @Size(max = 200) String sourceColumn,
            @Size(max = 200) String targetColumn,
            @Size(max = 100) String targetType,
            boolean required,
            boolean primaryKey,
            @Size(max = 4000) String transformation,
            @Size(max = 4000) String description,
            @Size(max = 50) String requirementKey) {

        static RuleDto from(MappingRule rule) {
            return new RuleDto(rule.getId(), rule.getSourceDataset(), rule.getSourceColumn(), rule.getTargetColumn(),
                    rule.getTargetType(), rule.isRequired(), rule.isPrimaryKey(), rule.getTransformation(),
                    rule.getDescription(), rule.getRequirementKey());
        }
    }

    /** {@code inherited} means the state comes from {@code originRelease} (null: the mainline), not from the release asked for. */
    public record MappingSummary(
            UUID id, UUID key, String name, String contractName, String contractVersion, ReviewStatus status,
            String ownerTeam, int ruleCount, Instant updatedAt, boolean inherited, String originRelease) {}

    public record MappingDetail(
            UUID id, UUID key, UUID releaseId, String releaseName, boolean archived, String name, String contractName, String contractVersion, String domain, String description,
            String targetTable, String ownerTeam, ReviewStatus status, String publishedVersion,
            String publishedCommit, String createdBy, Instant updatedAt, long version, List<RuleDto> rules,
            List<ValidationIssue> issues, List<EventDto> events, String contentHash,
            List<ConflictView> conflicts) {}

    public record EventDto(
            UUID id, ReviewEventType type, String actor, String comment, UUID ruleId, String contentHash,
            Instant createdAt) {

        public static EventDto from(ReviewEvent event) {
            return new EventDto(event.getId(), event.getType(), event.getActor(), event.getComment(),
                    event.getRuleId(), event.getContentHash(), event.getCreatedAt());
        }
    }

    public record ImportResult(int imported, int skippedRows, List<String> ignoredColumns, MappingDetail mapping) {}

    public record PreviewFile(String path, String content) {}
}
