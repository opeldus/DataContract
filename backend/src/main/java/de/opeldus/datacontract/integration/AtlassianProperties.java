package de.opeldus.datacontract.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.atlassian")
public record AtlassianProperties(String baseUrl, String email, String apiToken, String confluenceSpaceKey,
        String confluenceParentPageId) {}
