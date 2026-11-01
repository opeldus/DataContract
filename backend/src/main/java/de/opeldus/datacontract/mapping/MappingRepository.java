package de.opeldus.datacontract.mapping;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MappingRepository extends JpaRepository<Mapping, UUID> {

    boolean existsByContractName(String contractName);

    List<Mapping> findByScopeKeyIn(Collection<String> scopeKeys);

    List<Mapping> findByScopeKey(String scopeKey);

    Optional<Mapping> findByContractKeyAndScopeKey(UUID contractKey, String scopeKey);

    /** The name of a contract is the same in every release, so any copy can answer. */
    Optional<Mapping> findFirstByContractKey(UUID contractKey);
}
