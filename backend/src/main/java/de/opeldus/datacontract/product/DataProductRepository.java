package de.opeldus.datacontract.product;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataProductRepository extends JpaRepository<DataProduct, UUID> {

    boolean existsByProductName(String productName);

    List<DataProduct> findByScopeKeyIn(Collection<String> scopeKeys);

    List<DataProduct> findByScopeKey(String scopeKey);

    Optional<DataProduct> findByProductKeyAndScopeKey(UUID productKey, String scopeKey);
}
