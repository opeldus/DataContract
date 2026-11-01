package de.opeldus.datacontract.integration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface IntegrationTaskRepository extends JpaRepository<IntegrationTask, UUID> {

    List<IntegrationTask> findTop50ByStateAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(IntegrationTask.State state,
            Instant now);

    List<IntegrationTask> findBySubjectIdOrderByCreatedAtDesc(UUID subjectId);

    List<IntegrationTask> findBySubjectIdAndKindAndStateIn(UUID subjectId, IntegrationTask.Kind kind,
            List<IntegrationTask.State> states);
}
