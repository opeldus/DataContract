package de.opeldus.datacontract.product;

import de.opeldus.datacontract.mapping.MappingDtos.EventDto;
import de.opeldus.datacontract.release.ReleaseDtos.ConflictView;
import de.opeldus.datacontract.review.ReviewStatus;
import de.opeldus.datacontract.validation.ValidationIssue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ProductDtos {

    private ProductDtos() {}

    public record CreateProductRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,99}",
                    message = "nur Kleinbuchstaben, Ziffern, _ und - erlaubt") String productName,
            UUID releaseId) {}

    public record UpdateProductRequest(
            long version,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 50) String productVersion,
            @Size(max = 100) String domain,
            @Size(max = 50) String productType,
            @Size(max = 4000) String purpose,
            @Size(max = 100) String ownerTeam,
            @Valid List<PortDto> ports) {}

    public record PortDto(
            UUID id,
            @NotNull PortDirection direction,
            @Size(max = 200) String name,
            UUID contractKey,
            String contractName,
            @Size(max = 50) String contractVersion) {}

    /** {@code inherited} means the state comes from {@code originRelease} (null: the mainline), not from the release asked for. */
    public record ProductSummary(
            UUID id, UUID key, String name, String productName, String productVersion, ReviewStatus status,
            String ownerTeam, int inputPorts, int outputPorts, Instant updatedAt, boolean inherited,
            String originRelease) {}

    public record ProductDetail(
            UUID id, UUID key, UUID releaseId, String releaseName, boolean archived, String name, String productName, String productVersion, String domain, String productType,
            String purpose, String ownerTeam, ReviewStatus status, String publishedVersion, String publishedCommit,
            String createdBy, Instant updatedAt, long version, List<PortDto> ports, List<ValidationIssue> issues,
            List<EventDto> events, String contentHash, List<ConflictView> conflicts) {}

    /** {@code plannedVersion} is set if this release itself is about to publish that version. */
    public record ContractOption(UUID contractKey, String name, String domain, List<String> publishedVersions,
            String plannedVersion) {}

    public record ContractConsumer(
            UUID productId, String productName, String productTitle, ReviewStatus productStatus,
            PortDirection direction, String portName, String contractVersion) {}

    public record GraphNode(String id, String kind, String label, UUID subjectId, ReviewStatus status, String version) {}

    public record GraphEdge(String from, String to, String version) {}

    public record Graph(List<GraphNode> nodes, List<GraphEdge> edges) {}
}
