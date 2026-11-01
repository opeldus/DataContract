package de.opeldus.datacontract.artifact;

import de.opeldus.datacontract.mapping.Mapping;

/** Extension point: every bean is included in previews, the content hash and publishing. */
public interface ArtifactGenerator {

    /** Must be deterministic: the same mapping always yields byte-identical content. */
    GeneratedArtifact generate(Mapping mapping);
}
