package de.opeldus.datacontract.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CatalogColumnRepository extends JpaRepository<CatalogColumnEntity, UUID> {

    @Query("""
            select c from CatalogColumnEntity c
            where c.dataset.qualifiedName = :dataset
              and lower(c.name) like lower(concat('%', :query, '%')) escape '\\'
            order by c.name
            """)
    List<CatalogColumnEntity> search(@Param("dataset") String dataset, @Param("query") String query, Pageable pageable);

    @Query("""
            select c from CatalogColumnEntity c
            where c.dataset.qualifiedName = :dataset and lower(c.name) = lower(:name)
            """)
    Optional<CatalogColumnEntity> find(@Param("dataset") String dataset, @Param("name") String name);
}
