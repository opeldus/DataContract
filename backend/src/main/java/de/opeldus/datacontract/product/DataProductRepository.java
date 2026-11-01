package de.opeldus.datacontract.product;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataProductRepository extends JpaRepository<DataProduct, UUID> {

    boolean existsByProductName(String productName);

    @Query("select distinct p from DataProductPort port join port.product p where port.contract.id = :contractId")
    List<DataProduct> findByContract(@Param("contractId") UUID contractId);
}
