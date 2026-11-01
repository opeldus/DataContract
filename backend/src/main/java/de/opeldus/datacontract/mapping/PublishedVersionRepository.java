package de.opeldus.datacontract.mapping;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublishedVersionRepository extends JpaRepository<PublishedVersion, PublishedVersion.Key> {

    List<PublishedVersion> findBySubjectKey(UUID subjectKey);

    List<PublishedVersion> findByReleaseId(UUID releaseId);
}
