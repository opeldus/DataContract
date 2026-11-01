package de.opeldus.datacontract.integration;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface IntegrationPageRepository extends JpaRepository<IntegrationPage, UUID> {}
