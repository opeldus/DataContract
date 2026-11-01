package de.opeldus.datacontract.integration;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Confluence Cloud REST API v2. */
@Component
@ConditionalOnExpression("!'${app.atlassian.base-url:}'.isBlank() and !'${app.atlassian.confluence-space-key:}'.isBlank()")
public class ConfluenceCloudClient implements DocumentationPublisher {

    private final RestClient client;
    private final AtlassianProperties properties;
    private final String baseUrl;
    private volatile String spaceId;

    public ConfluenceCloudClient(RestClient.Builder builder, AtlassianProperties properties) {
        this.client = AtlassianClientFactory.create(builder, properties);
        this.properties = properties;
        this.baseUrl = properties.baseUrl().replaceAll("/+$", "");
    }

    @Override
    public PublishedPage upsert(String knownPageId, String title, String body, String marker, String versionMessage) {
        var existing = knownPageId == null ? null : getById(knownPageId);
        if (existing == null) {
            existing = findByTitle(title);
            if (existing != null && !existing.path("body").path("storage").path("value").asString("").contains(marker)) {
                throw new IntegrationException("In Confluence gibt es bereits eine fremde Seite mit dem Titel '" + title
                        + "'. Bitte umbenennen, sie wird nicht überschrieben.");
            }
        }
        var response = existing == null
                ? create(title, body)
                : update(existing, title, body, versionMessage);
        return toPage(response);
    }

    private JsonNode getById(String pageId) {
        try {
            return client.get()
                    .uri(uri -> uri.path("/wiki/api/v2/pages/{id}").queryParam("body-format", "storage").build(pageId))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound deleted) {
            return null;
        }
    }

    private JsonNode findByTitle(String title) {
        var response = client.get()
                .uri(uri -> uri.path("/wiki/api/v2/pages")
                        .queryParam("space-id", "{space}")
                        .queryParam("title", "{title}")
                        .queryParam("body-format", "storage")
                        .build(spaceId(), title))
                .retrieve()
                .body(JsonNode.class);
        var results = response.path("results");
        return results.isEmpty() ? null : results.get(0);
    }

    private JsonNode create(String title, String body) {
        var request = new LinkedHashMap<String, Object>();
        request.put("spaceId", spaceId());
        request.put("status", "current");
        request.put("title", title);
        if (StringUtils.hasText(properties.confluenceParentPageId())) {
            request.put("parentId", properties.confluenceParentPageId());
        }
        request.put("body", storage(body));
        return client.post()
                .uri("/wiki/api/v2/pages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode update(JsonNode existing, String title, String body, String versionMessage) {
        var id = existing.path("id").asString("");
        var version = new HashMap<String, Object>();
        version.put("number", existing.path("version").path("number").asInt() + 1);
        version.put("message", versionMessage);
        return client.put()
                .uri("/wiki/api/v2/pages/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("id", id, "status", "current", "title", title, "body", storage(body), "version", version))
                .retrieve()
                .body(JsonNode.class);
    }

    private static Map<String, Object> storage(String body) {
        return Map.of("representation", "storage", "value", body);
    }

    private String spaceId() {
        if (spaceId == null) {
            var response = client.get()
                    .uri(uri -> uri.path("/wiki/api/v2/spaces").queryParam("keys", "{key}")
                            .build(properties.confluenceSpaceKey()))
                    .retrieve()
                    .body(JsonNode.class);
            var results = response.path("results");
            if (results.isEmpty()) {
                throw new IntegrationException("Der Confluence-Bereich '" + properties.confluenceSpaceKey()
                        + "' wurde nicht gefunden, oder dem Zugang fehlen die Rechte.");
            }
            spaceId = results.get(0).path("id").asString("");
        }
        return spaceId;
    }

    private PublishedPage toPage(JsonNode page) {
        var webui = page.path("_links").path("webui").asString("");
        var linkBase = page.path("_links").path("base").asString(baseUrl + "/wiki");
        return new PublishedPage(page.path("id").asString(""), webui.isEmpty() ? null : linkBase + webui);
    }
}
