package de.opeldus.datacontract.integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Jira Cloud REST API v3. */
@Component
@ConditionalOnExpression("!'${app.atlassian.base-url:}'.isBlank()")
public class JiraCloudClient implements IssueTracker {

    private static final int BATCH_SIZE = 100;
    private static final String APPLICATION_TYPE = "de.opeldus.datacontract";

    private final RestClient client;
    private final String baseUrl;

    public JiraCloudClient(RestClient.Builder builder, AtlassianProperties properties) {
        client = AtlassianClientFactory.create(builder, properties);
        baseUrl = properties.baseUrl().replaceAll("/+$", "");
    }

    @Override
    public List<Issue> search(String query) {
        var response = client.get()
                .uri(uri -> uri.path("/rest/api/3/issue/picker").queryParam("query", "{query}").build(query))
                .retrieve()
                .body(JsonNode.class);
        var issues = new LinkedHashMap<String, Issue>();
        for (var section : response.path("sections")) {
            for (var issue : section.path("issues")) {
                var key = issue.path("key").asString("");
                issues.putIfAbsent(key, new Issue(key, issue.path("summaryText").asString(""), null, null, null));
            }
        }
        return List.copyOf(issues.values());
    }

    @Override
    public Lookup fetch(Collection<String> keys) {
        var issues = new ArrayList<Issue>();
        var retriable = new ArrayList<String>();
        var all = List.copyOf(keys);
        for (int start = 0; start < all.size(); start += BATCH_SIZE) {
            var batch = all.subList(start, Math.min(start + BATCH_SIZE, all.size()));
            var response = client.post()
                    .uri("/rest/api/3/issue/bulkfetch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("issueIdsOrKeys", batch, "fields", List.of("summary", "status", "issuetype")))
                    .retrieve()
                    .body(JsonNode.class);
            for (var issue : response.path("issues")) {
                var fields = issue.path("fields");
                issues.add(new Issue(issue.path("key").asString(""), fields.path("summary").asString(""),
                        fields.path("status").path("name").asString(""),
                        fields.path("status").path("statusCategory").path("key").asString(""),
                        fields.path("issuetype").path("name").asString("")));
            }
            for (var error : response.path("issueErrors")) {
                retriable.add(error.path("id").asString(""));
            }
        }
        return new Lookup(issues, retriable);
    }

    @Override
    public void upsertLink(String issueKey, RemoteLink link) {
        var object = new LinkedHashMap<String, Object>();
        object.put("url", link.url());
        object.put("title", link.title());
        object.put("summary", link.summary());
        client.post()
                .uri("/rest/api/3/issue/{key}/remotelink", issueKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "globalId", link.globalId(),
                        "application", Map.of("type", APPLICATION_TYPE, "name", "DataContract"),
                        "relationship", "Data Contract",
                        "object", object))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void removeLink(String issueKey, String globalId) {
        try {
            client.delete()
                    .uri(uri -> uri.path("/rest/api/3/issue/{key}/remotelink").queryParam("globalId", "{id}")
                            .build(issueKey, globalId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound alreadyGone) {
            // The goal is reached: the link does not exist.
        }
    }

    @Override
    public String issueUrl(String issueKey) {
        return baseUrl + "/browse/" + issueKey;
    }
}
