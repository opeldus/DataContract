package de.opeldus.datacontract.catalog;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CatalogDatasetRepository extends JpaRepository<CatalogDatasetEntity, String> {

    @Query("""
            select d from CatalogDatasetEntity d
            where lower(d.qualifiedName) like lower(concat('%', :query, '%')) escape '\\'
            order by d.qualifiedName
            """)
    List<CatalogDatasetEntity> search(@Param("query") String query, Pageable pageable);
}
