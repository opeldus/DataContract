package de.opeldus.datacontract.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class DataHubCatalogProviderTest {

    private HttpServer server;
    private final List<String> requestBodies = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void followsScrollPagesAndMapsFields() {
        var responses = List.of("""
                {"data":{"scrollAcrossEntities":{"nextScrollId":"page2","searchResults":[{"entity":{
                  "urn":"urn:li:dataset:(urn:li:dataPlatform:db2,SALES.KUNDE,PROD)","name":"SALES.KUNDE",
                  "platform":{"name":"db2"},
                  "schemaMetadata":{"fields":[
                    {"fieldPath":"KUNDEN_NR","nativeDataType":"CHAR(10)","nullable":false,"description":"Nummer"},
                    {"fieldPath":"[version=2.0].[type=string].NAME","nativeDataType":null,"nullable":true,"description":null}
                  ]}}}]}}}
                """, """
                {"data":{"scrollAcrossEntities":{"nextScrollId":null,"searchResults":[{"entity":{
                  "urn":"urn:li:dataset:(urn:li:dataPlatform:snowflake,DWH.CORE.X,PROD)","name":"DWH.CORE.X",
                  "platform":{"name":"snowflake"},"schemaMetadata":null}}]}}}
                """);
        var index = new AtomicInteger();
        serve(() -> responses.get(index.getAndIncrement()));

        var datasets = provider("secret").fetchDatasets();

        assertThat(datasets).extracting(CatalogProvider.Dataset::qualifiedName)
                .containsExactly("db2.SALES.KUNDE", "snowflake.DWH.CORE.X");
        var columns = datasets.get(0).columns();
        assertThat(columns.get(0)).isEqualTo(new CatalogProvider.Column("KUNDEN_NR", "CHAR(10)", false, "Nummer"));
        assertThat(columns.get(1)).isEqualTo(new CatalogProvider.Column("NAME", null, true, null));
        assertThat(datasets.get(1).columns()).isEmpty();
        assertThat(requestBodies.get(1)).contains("\"scrollId\":\"page2\"");
        assertThat(authHeaders).containsOnly("Bearer secret");
    }

    @Test
    void graphQlErrorsFailTheRefreshInsteadOfEmptyingTheIndex() {
        serve(() -> "{\"errors\":[{\"message\":\"Unauthorized\"}],\"data\":null}");

        assertThatThrownBy(() -> provider("").fetchDatasets()).hasMessageContaining("Unauthorized");
    }

    private DataHubCatalogProvider provider(String token) {
        var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        return new DataHubCatalogProvider(RestClient.builder(), url, token);
    }

    private void serve(java.util.function.Supplier<String> response) {
        server.createContext("/api/graphql", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            var bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }
}
