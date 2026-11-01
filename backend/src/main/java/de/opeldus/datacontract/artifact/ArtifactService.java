package de.opeldus.datacontract.artifact;

import de.opeldus.datacontract.mapping.Mapping;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ArtifactService {

    private final List<ArtifactGenerator> generators;

    public ArtifactService(List<ArtifactGenerator> generators) {
        this.generators = generators;
    }

    public List<GeneratedArtifact> generate(Mapping mapping) {
        return generators.stream()
                .map(generator -> generator.generate(mapping))
                .sorted(Comparator.comparing(GeneratedArtifact::path))
                .toList();
    }

    /** Pins an approval to exactly the content the reviewer saw. */
    public String contentHash(Mapping mapping) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var artifact : generate(mapping)) {
                digest.update(artifact.path().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(artifact.content().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
