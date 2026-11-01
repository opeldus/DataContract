package de.opeldus.datacontract.mapping;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MappingRepository extends JpaRepository<Mapping, UUID> {

    boolean existsByContractName(String contractName);
}
