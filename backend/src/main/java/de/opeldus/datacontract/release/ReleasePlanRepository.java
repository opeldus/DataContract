package de.opeldus.datacontract.release;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReleasePlanRepository extends JpaRepository<ReleasePlan, UUID> {

    boolean existsByName(String name);

    List<ReleasePlan> findByBaseIdAndState(UUID baseId, ReleaseState state);

    List<ReleasePlan> findByBaseIdIsNullAndState(ReleaseState state);
}
