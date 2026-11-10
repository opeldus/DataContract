package de.opeldus.datacontract.etl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EtlJobRepository extends JpaRepository<EtlJobRecord, UUID> {

    Optional<EtlJobRecord> findByName(String name);

    List<EtlJobRecord> findAllByOrderByNameAsc();
}
