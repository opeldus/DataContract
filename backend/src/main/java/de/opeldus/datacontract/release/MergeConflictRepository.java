package de.opeldus.datacontract.release;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MergeConflictRepository extends JpaRepository<MergeConflict, UUID> {

    List<MergeConflict> findBySubjectIdAndStateOrderByCreatedAtAsc(UUID subjectId, MergeConflict.State state);

    long countBySubjectIdAndState(UUID subjectId, MergeConflict.State state);
}
