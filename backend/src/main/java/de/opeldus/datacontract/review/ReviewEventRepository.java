package de.opeldus.datacontract.review;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewEventRepository extends JpaRepository<ReviewEvent, UUID> {

    List<ReviewEvent> findBySubjectIdOrderByCreatedAtAsc(UUID subjectId);
}
