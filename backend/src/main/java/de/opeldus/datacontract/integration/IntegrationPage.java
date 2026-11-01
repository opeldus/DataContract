package de.opeldus.datacontract.integration;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "integration_page")
class IntegrationPage {

    @Id
    UUID subjectId;

    String pageId;
    String url;
    Instant updatedAt = Instant.now();

    protected IntegrationPage() {}

    IntegrationPage(UUID subjectId) {
        this.subjectId = subjectId;
    }
}
