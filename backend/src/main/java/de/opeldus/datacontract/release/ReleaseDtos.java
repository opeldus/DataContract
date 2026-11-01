package de.opeldus.datacontract.release;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReleaseDtos {

    private ReleaseDtos() {}

    public record CreateReleaseRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9_-]{0,49}",
                    message = "nur Kleinbuchstaben, Ziffern, _ und - erlaubt") String name,
            @NotBlank @Size(max = 200) String title,
            UUID baseId) {}

    public record ReleaseItem(String kind, UUID id, UUID key, String name, String version, String status,
            int openConflicts) {}

    public record ReleaseView(UUID id, String name, String title, UUID baseId, String baseName, String state,
            String branch, String createdBy, Instant createdAt, Instant releasedAt, String releasedCommit,
            List<ReleaseItem> items, List<String> closeBlockers) {}

    public record ConflictView(UUID id, UUID itemId, String field, String label, String ours, String theirs,
            boolean removedUpstream, boolean removedHere, String upstreamVersion) {

        public static ConflictView from(MergeConflict conflict) {
            var itemLevel = conflict.getField() == null;
            return new ConflictView(conflict.getId(), conflict.getItemId(), conflict.getField(), conflict.getLabel(),
                    conflict.getOurs(), conflict.getTheirs(), itemLevel && conflict.getTheirsItem() == null,
                    itemLevel && conflict.getTheirsItem() != null, conflict.getUpstreamVersion());
        }
    }
}
