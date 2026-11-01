package de.opeldus.datacontract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductWorkflowTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @TempDir
    static Path temp;

    private static Path remote;

    @DynamicPropertySource
    static void gitProperties(DynamicPropertyRegistry registry) throws Exception {
        remote = temp.resolve("remote.git");
        Git.init().setBare(true).setDirectory(remote.toFile()).setInitialBranch("main").call().close();
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:products;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        registry.add("app.git.workdir", () -> temp.resolve("workspace").toString());
        registry.add("app.git.remote-url", () -> remote.toUri().toString());
    }

    @Autowired
    MockMvc mvc;

    private static RequestPostProcessor anna() {
        return user("anna").roles("EDITOR");
    }

    private static RequestPostProcessor rene() {
        return user("rene").roles("REVIEWER");
    }

    @Test
    void productIsReviewedAndPublishedAsOdpsThatReferencesTheOdcsContracts() throws Exception {
        var source = publishedContract("quelle-" + shortId(), "1.0.0");
        var target = publishedContract("ziel-" + shortId(), "1.0.0");
        var product = create("produkt-" + shortId());
        var updated = update(product, List.of(
                port("INPUT", "kunden", source, "1.0.0"),
                port("OUTPUT", "kunden_mart", target, "1.0.0")));
        assertThat(errors(updated)).isEmpty();

        var id = updated.path("id").asText();
        var inReview = act("/api/products/" + id + "/submit", null, anna());
        act("/api/products/" + id + "/approve", Map.of("contentHash", inReview.path("contentHash").asText()), rene());
        var published = act("/api/products/" + id + "/publish", null, rene());
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");

        var clone = temp.resolve("verify-" + shortId());
        Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(clone.toFile()).call().close();
        var name = published.path("productName").asText();
        var odps = YAML.readTree(Files.readString(clone.resolve("products/" + name + "/odps.yaml")));
        var sourceId = "urn:datacontract:" + source;
        assertThat(odps.path("apiVersion").asText()).isEqualTo("v1.1.0");
        assertThat(odps.path("inputPorts").get(0).path("contractId").asText()).isEqualTo(sourceId);
        assertThat(odps.path("outputPorts").get(0).path("contractId").asText()).isEqualTo("urn:datacontract:" + target);
        assertThat(odps.path("outputPorts").get(0).path("inputContracts").get(0).path("id").asText()).isEqualTo(sourceId);

        var sourceContract = YAML.readTree(Files.readString(clone.resolve(contractPath(source))));
        assertThat(sourceContract.path("id").asText()).isEqualTo(sourceId);
    }

    @Test
    void outputPortNeedsAPublishedContractInAnExistingVersion() throws Exception {
        var unpublished = createMapping("offen-" + shortId());
        var published = publishedContract("fertig-" + shortId(), "1.0.0");
        var product = create("regeln-" + shortId());

        var noContract = update(product, List.of(port("OUTPUT", "a", null, null)));
        assertThat(codes(noContract)).contains("OUTPUT_WITHOUT_CONTRACT", "NO_INPUT_PORT");

        var notPublished = update(noContract, List.of(port("OUTPUT", "a", unpublished, null)));
        assertThat(codes(notPublished)).contains("CONTRACT_NOT_PUBLISHED");

        var wrongVersion = update(notPublished, List.of(port("OUTPUT", "a", published, "9.9.9")));
        assertThat(codes(wrongVersion)).contains("CONTRACT_VERSION_UNKNOWN");
    }

    @Test
    void submitIsBlockedWhileValidationErrorsExist() throws Exception {
        var product = create("leer-" + shortId());

        mvc.perform(post("/api/products/" + product.path("id").asText() + "/submit").with(anna()).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void contractCanOnlyHaveOneProducer() throws Exception {
        var contract = publishedContract("einer-" + shortId(), "1.0.0");
        update(create("erster-" + shortId()), List.of(port("OUTPUT", "a", contract, "1.0.0")));

        var second = update(create("zweiter-" + shortId()), List.of(port("OUTPUT", "b", contract, "1.0.0")));

        assertThat(codes(second)).contains("CONTRACT_HAS_OTHER_PRODUCER");
    }

    @Test
    void cyclesAcrossProductsAreDetected() throws Exception {
        var first = publishedContract("kreis-a-" + shortId(), "1.0.0");
        var second = publishedContract("kreis-b-" + shortId(), "1.0.0");
        var productA = update(create("kreis-pa-" + shortId()), List.of(
                port("INPUT", "in", second, "1.0.0"), port("OUTPUT", "out", first, "1.0.0")));
        assertThat(codes(productA)).doesNotContain("DEPENDENCY_CYCLE");

        var productB = update(create("kreis-pb-" + shortId()), List.of(
                port("INPUT", "in", first, "1.0.0"), port("OUTPUT", "out", second, "1.0.0")));

        assertThat(codes(productB)).contains("DEPENDENCY_CYCLE");
    }

    @Test
    void productUsingOwnOutputAsInputIsACycle() throws Exception {
        var contract = publishedContract("selbst-" + shortId(), "1.0.0");

        var product = update(create("selbst-p-" + shortId()), List.of(
                port("INPUT", "in", contract, "1.0.0"), port("OUTPUT", "out", contract, "1.0.0")));

        assertThat(codes(product)).contains("DEPENDENCY_CYCLE");
    }

    @Test
    void portOnOlderContractVersionGetsAWarningAndConsumersAreListed() throws Exception {
        var contractName = "wachsend-" + shortId();
        var contract = publishedContract(contractName, "1.0.0");
        var other = publishedContract("ausgabe-" + shortId(), "1.0.0");
        var product = update(create("nutzer-" + shortId()), List.of(
                port("INPUT", "in", contract, "1.0.0"), port("OUTPUT", "out", other, "1.0.0")));
        assertThat(codes(product)).doesNotContain("CONTRACT_NEW_MAJOR");

        republish(contract, "2.0.0");

        var reloaded = read("/api/products/" + product.path("id").asText());
        assertThat(codes(reloaded)).contains("CONTRACT_NEW_MAJOR");
        assertThat(errors(reloaded)).isEmpty();

        var consumers = read("/api/mappings/" + contract + "/consumers");
        assertThat(consumers.get(0).path("productName").asText()).isEqualTo(product.path("productName").asText());
        assertThat(consumers.get(0).path("direction").asText()).isEqualTo("INPUT");

        var options = read("/api/contracts");
        var option = findBy(options, "name", contractName);
        assertThat(option.path("publishedVersions").get(0).asText()).isEqualTo("2.0.0");
        assertThat(option.path("publishedVersions").get(1).asText()).isEqualTo("1.0.0");
    }

    @Test
    void graphConnectsContractsAndProductsInDataFlowDirection() throws Exception {
        var input = publishedContract("graph-in-" + shortId(), "1.0.0");
        var output = publishedContract("graph-out-" + shortId(), "1.0.0");
        var product = update(create("graph-p-" + shortId()), List.of(
                port("INPUT", "in", input, "1.0.0"), port("OUTPUT", "out", output, "1.0.0")));

        var graph = read("/api/graph");
        var productNode = "product:" + product.path("id").asText();
        var edges = new ArrayList<String>();
        graph.path("edges").forEach(edge -> edges.add(edge.path("from").asText() + ">" + edge.path("to").asText()));

        assertThat(edges).contains("contract:" + input + ">" + productNode, productNode + ">contract:" + output);
    }

    @Test
    void unknownContractIsRejected() throws Exception {
        var product = create("unbekannt-" + shortId());

        mvc.perform(put("/api/products/" + product.path("id").asText()).with(anna()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body(product, List.of(
                                port("OUTPUT", "a", UUID.randomUUID(), "1.0.0"))))))
                .andExpect(status().isBadRequest());
    }

    private UUID publishedContract(String name, String version) throws Exception {
        var id = createMapping(name);
        publishMapping(id, version);
        return id;
    }

    private UUID createMapping(String name) throws Exception {
        var created = send(post("/api/mappings"), Map.of("name", name, "contractName", name), anna(), 201);
        return UUID.fromString(created.path("id").asText());
    }

    private void publishMapping(UUID id, String version) throws Exception {
        var current = read("/api/mappings/" + id);
        var rule = new HashMap<String, Object>();
        rule.put("sourceDataset", "db2.SALES.KUNDE");
        rule.put("sourceColumn", "KUNDEN_NR");
        rule.put("targetColumn", "KUNDEN_NR");
        rule.put("targetType", "VARCHAR(10)");
        rule.put("required", false);
        rule.put("primaryKey", false);
        rule.put("requirementKey", "DATA-1");
        var body = new HashMap<String, Object>();
        body.put("version", current.path("version").asLong());
        body.put("name", current.path("name").asText());
        body.put("contractVersion", version);
        body.put("domain", "sales");
        body.put("targetTable", "ZIEL");
        body.put("ownerTeam", "data-team");
        body.put("rules", List.of(rule));
        send(put("/api/mappings/" + id), body, anna(), 200);
        var inReview = act("/api/mappings/" + id + "/submit", null, anna());
        act("/api/mappings/" + id + "/approve", Map.of("contentHash", inReview.path("contentHash").asText()), rene());
        act("/api/mappings/" + id + "/publish", null, rene());
    }

    private void republish(UUID id, String version) throws Exception {
        act("/api/mappings/" + id + "/reopen", null, anna());
        publishMapping(id, version);
    }

    private String contractPath(UUID mappingId) throws Exception {
        return "contracts/" + read("/api/mappings/" + mappingId).path("contractName").asText() + "/odcs.yaml";
    }

    private JsonNode create(String productName) throws Exception {
        return send(post("/api/products"), Map.of("name", "Produkt", "productName", productName), anna(), 201);
    }

    private JsonNode update(JsonNode current, List<Map<String, Object>> ports) throws Exception {
        return send(put("/api/products/" + current.path("id").asText()), body(current, ports), anna(), 200);
    }

    private static Map<String, Object> body(JsonNode current, List<Map<String, Object>> ports) {
        var body = new HashMap<String, Object>();
        body.put("version", current.path("version").asLong());
        body.put("name", "Produkt");
        body.put("productVersion", "1.0.0");
        body.put("domain", "sales");
        body.put("productType", "consumerAligned");
        body.put("purpose", "Reporting");
        body.put("ownerTeam", "data-team");
        body.put("ports", ports);
        return body;
    }

    private static Map<String, Object> port(String direction, String name, UUID contract, String version) {
        var port = new HashMap<String, Object>();
        port.put("direction", direction);
        port.put("name", name);
        port.put("contractMappingId", contract);
        port.put("contractVersion", version);
        return port;
    }

    private JsonNode act(String path, Map<String, ?> body, RequestPostProcessor actor) throws Exception {
        return send(post(path), body, actor, 200);
    }

    private JsonNode send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            Object body, RequestPostProcessor actor, int expectedStatus) throws Exception {
        request.with(actor).with(csrf());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        var result = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode read(String path) throws Exception {
        var result = mvc.perform(get(path).with(anna())).andExpect(status().isOk()).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode findBy(JsonNode array, String field, String value) {
        for (var item : array) {
            if (value.equals(item.path(field).asText())) {
                return item;
            }
        }
        throw new AssertionError(value + " not found in " + array);
    }

    private static List<String> codes(JsonNode item) {
        return item.path("issues").findValuesAsText("code");
    }

    private static List<String> errors(JsonNode item) {
        var errors = new ArrayList<String>();
        item.path("issues").forEach(issue -> {
            if ("ERROR".equals(issue.path("severity").asText())) {
                errors.add(issue.path("code").asText() + ": " + issue.path("message").asText());
            }
        });
        return errors;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
