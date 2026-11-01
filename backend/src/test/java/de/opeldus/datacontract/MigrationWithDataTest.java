package de.opeldus.datacontract;

import static org.assertj.core.api.Assertions.assertThat;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Upgrades a populated database on real PostgreSQL, because rebuilding tables must not lose or detach anything. */
class MigrationWithDataTest {

    @Test
    void releaseMigrationKeepsExistingContentOnPostgres() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().start()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).target("4").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);

            var mapping = UUID.randomUUID();
            var rule = UUID.randomUUID();
            var product = UUID.randomUUID();
            var port = UUID.randomUUID();
            jdbc.update("""
                    insert into mapping (id, name, contract_name, contract_version, status, published_version,
                        published_commit, created_by, created_at, updated_at, version)
                    values (?, 'Kunden', 'kunden', '1.0.0', 'PUBLISHED', '1.0.0', 'abc', 'anna', now(), now(), 3)
                    """, mapping);
            jdbc.update("""
                    insert into mapping_rule (id, mapping_id, sort_order, target_column, required, primary_key)
                    values (?, ?, 0, 'A', false, false)
                    """, rule, mapping);
            jdbc.update("""
                    insert into contract_version (mapping_id, version, commit_id, published_at, snapshot)
                    values (?, '1.0.0', 'abc', now(), '{}')
                    """, mapping);
            jdbc.update("""
                    insert into data_product (id, name, product_name, product_version, status, created_by, created_at,
                        updated_at, version)
                    values (?, 'Produkt', 'produkt', '1.0.0', 'DRAFT', 'anna', now(), now(), 1)
                    """, product);
            jdbc.update("""
                    insert into data_product_port (id, product_id, direction, sort_order, name, contract_id, contract_version)
                    values (?, ?, 'INPUT', 0, 'in', ?, '1.0.0')
                    """, port, product, mapping);

            Flyway.configure().dataSource(dataSource).load().migrate();

            var row = jdbc.queryForMap("select * from mapping where id = ?", mapping);
            assertThat(row.get("contract_key")).isEqualTo(mapping);
            assertThat(row.get("scope_key")).isEqualTo("main");
            assertThat(row.get("release_id")).isNull();
            assertThat(row.get("version")).isEqualTo(3L);
            assertThat(jdbc.queryForObject("select lineage_id from mapping_rule where id = ?", UUID.class, rule)).isEqualTo(rule);
            assertThat(jdbc.queryForObject("select count(*) from published_version where subject_key = ?", Long.class, mapping)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select product_key from data_product where id = ?", UUID.class, product)).isEqualTo(product);
            assertThat(jdbc.queryForObject("select contract_key from data_product_port where id = ?", UUID.class, port)).isEqualTo(mapping);

            jdbc.update("delete from mapping where id = ?", mapping);
            assertThat(jdbc.queryForObject("select count(*) from mapping_rule where id = ?", Long.class, rule)).isZero();
        }
    }
}
