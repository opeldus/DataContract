package de.opeldus.datacontract.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Reads datasets and their schema fields through DataHub's GraphQL API. */
@Component
@ConditionalOnExpression("!'${app.catalog.datahub.url:}'.isBlank()")
public class DataHubCatalogProvider implements CatalogProvider {

    private static final int PAGE_SIZE = 200;
    private static final String QUERY = """
            query datasets($input: ScrollAcrossEntitiesInput!) {
              scrollAcrossEntities(input: $input) {
                nextScrollId
                searchResults {
                  entity {
                    urn
                    ... on Dataset {
                      name
                      platform { name }
                      schemaMetadata(version: 0) {
                        fields { fieldPath nativeDataType nullable description }
                      }
                    }
                  }
                }
              }
            }
            """;

    private final RestClient client;

    public DataHubCatalogProvider(RestClient.Builder builder, @Value("${app.catalog.datahub.url}") String url,
            @Value("${app.catalog.datahub.token:}") String token) {
        var configured = builder.baseUrl(url.replaceAll("/+$", ""));
        if (!token.isBlank()) {
            configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        client = configured.build();
    }

    @Override
    public List<Dataset> fetchDatasets() {
        var datasets = new ArrayList<Dataset>();
        String scrollId = null;
        do {
            var page = fetchPage(scrollId);
            if (page.path("errors").isArray() && !page.path("errors").isEmpty()) {
                throw new IllegalStateException("DataHub GraphQL error: " + page.path("errors"));
            }
            var results = page.path("data").path("scrollAcrossEntities");
            for (var result : results.path("searchResults")) {
                datasets.add(toDataset(result.path("entity")));
            }
            var next = results.path("nextScrollId");
            scrollId = next.isNull() || next.isMissingNode() ? null : next.asString();
        } while (scrollId != null);
        return datasets;
    }

    private JsonNode fetchPage(String scrollId) {
        var input = new HashMap<String, Object>();
        input.put("types", List.of("DATASET"));
        input.put("query", "*");
        input.put("count", PAGE_SIZE);
        if (scrollId != null) {
            input.put("scrollId", scrollId);
        }
        return client.post()
                .uri("/api/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", QUERY, "variables", Map.of("input", input)))
                .retrieve()
                .body(JsonNode.class);
    }

    private Dataset toDataset(JsonNode entity) {
        var platform = entity.path("platform").path("name").asString("");
        var name = entity.path("name").asString("");
        var columns = new ArrayList<Column>();
        for (var field : entity.path("schemaMetadata").path("fields")) {
            columns.add(new Column(columnName(field.path("fieldPath").asString("")),
                    field.path("nativeDataType").isNull() ? null : field.path("nativeDataType").asString(null),
                    field.path("nullable").asBoolean(true),
                    field.path("description").isNull() ? null : field.path("description").asString(null)));
        }
        return new Dataset(platform + "." + name, entity.path("urn").asString(""), platform, columns);
    }

    /** Field paths in DataHub's v2 format carry markers such as "[version=2.0].[type=string].NAME". */
    static String columnName(String fieldPath) {
        return fieldPath.replaceAll("\\[[^\\]]*\\]\\.?", "");
    }
}
