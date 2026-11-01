package de.opeldus.datacontract;

import static org.assertj.core.api.Assertions.assertThat;

import de.opeldus.datacontract.mapping.MappingDtos.CreateMappingRequest;
import de.opeldus.datacontract.mapping.MappingService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the Flyway migrations and Hibernate's schema validation against a real PostgreSQL. */
@SpringBootTest
@ActiveProfiles("test")
class PostgresMigrationTest {

    @TempDir
    static java.nio.file.Path temp;

    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("app.git.workdir", () -> temp.resolve("workspace").toString());
    }

    @AfterAll
    static void stop() throws IOException {
        postgres.close();
    }

    @Autowired
    MappingService mappings;

    @Test
    void schemaMatchesEntitiesOnPostgres() {
        var created = mappings.create(new CreateMappingRequest("Kunden", "kunden"), "anna");

        assertThat(mappings.get(created.id()).contractName()).isEqualTo("kunden");
        assertThat(mappings.get(created.id()).events()).hasSize(1);
    }
}
