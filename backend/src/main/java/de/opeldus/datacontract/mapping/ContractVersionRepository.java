package de.opeldus.datacontract.mapping;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractVersionRepository extends JpaRepository<ContractVersion, ContractVersion.Key> {

    List<ContractVersion> findByMappingId(UUID mappingId);
}
