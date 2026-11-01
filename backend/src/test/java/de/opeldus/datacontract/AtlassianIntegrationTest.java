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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.opeldus.datacontract.integration.IntegrationTaskWorker;
import de.opeldus.datacontract.integration.RequirementDirectory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Runs against a local server that follows Atlassian's published Jira v3 and Confluence v2 specifications. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AtlassianIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "geheimes-api-token";

    @TempDir
    static Path temp;

    private static final FakeAtlassian atlassian = new FakeAtlassian();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        atlassian.start();
        var artifacts = temp.resolve("artifacts.git");
        Git.init().setBare(true).setDirectory(artifacts.toFile()).setInitialBranch("main").call().close();
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:atlassian;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        registry.add("app.git.workdir", () -> temp.resolve("workspace").toString());
        registry.add("app.git.remote-url", () -> artifacts.toUri().toString());
        registry.add("app.public-url", () -> "https://datacontract.example.invalid/");
        registry.add("app.atlassian.base-url", () -> "http://127.0.0.1:" + atlassian.port() + "/");
        registry.add("app.atlassian.email", () -> "bot@example.invalid");
        registry.add("app.atlassian.api-token", () -> SECRET);
        registry.add("app.atlassian.confluence-space-key", () -> "DATA");
        registry.add("app.atlassian.confluence-parent-page-id", () -> "999");
        registry.add("app.atlassian.task-interval", () -> "PT1H");
    }

    @AfterAll
    static void stop() {
        atlassian.stop();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    IntegrationTaskWorker worker;

    @Autowired
    RequirementDirectory directory;

    @BeforeEach
    void reset() {
        atlassian.reset();
    }

    private static RequestPostProcessor anna() {
        return user("anna").roles("EDITOR");
    }

    private static RequestPostProcessor rene() {
        return user("rene").roles("REVIEWER");
    }

    @Test
    void autocompleteUsesJiraPickerAndSendsCredentials() throws Exception {
        var result = read("/api/requirements/search?q=kunde");

        assertThat(result.get(0).path("key").asText()).isEqualTo("DATA-1");
        assertThat(result.get(0).path("summary").asText()).isEqualTo("Kundenstamm bereitstellen");
        var expected = "Basic " + Base64.getEncoder().encodeToString(("bot@example.invalid:" + SECRET).getBytes());
        assertThat(atlassian.authHeaders).containsOnly(expected);
    }

    @Test
    void unknownIssueBlocksSubmitAndKnownIssuePasses() throws Exception {
        var id = createMapping("jira-pruefung-" + shortId());

        var missing = save(id, List.of(rule("A", "DATA-404")), "1.0.0");
        assertThat(codes(missing)).contains("REQUIREMENT_NOT_FOUND");
        mvc.perform(post("/api/mappings/" + id + "/submit").with(anna()).with(csrf()))
                .andExpect(status().isConflict());

        var known = save(id, List.of(rule("A", "DATA-1")), "1.0.0");
        assertThat(codes(known)).doesNotContain("REQUIREMENT_NOT_FOUND");
    }

    @Test
    void issueCreatedAfterTheFirstCheckIsRecognizedOnTheNextSave() throws Exception {
        var id = createMapping("jira-spaeter-" + shortId());
        assertThat(codes(save(id, List.of(rule("A", "DATA-500")), "1.0.0"))).contains("REQUIREMENT_NOT_FOUND");

        atlassian.laterIssues.put("DATA-500", "Inzwischen angelegt");

        assertThat(codes(save(id, List.of(rule("A", "DATA-500")), "1.0.0"))).doesNotContain("REQUIREMENT_NOT_FOUND");
    }

    @Test
    void textThatIsNoIssueKeyOnlyWarnsAndIsNeverSentToJira() throws Exception {
        var id = createMapping("jira-format-" + shortId());
        atlassian.requests.clear();

        var saved = save(id, List.of(rule("A", "../../admin")), "1.0.0");

        assertThat(codes(saved)).contains("REQUIREMENT_FORMAT").doesNotContain("REQUIREMENT_NOT_FOUND");
        assertThat(atlassian.requests).isEmpty();
    }

    @Test
    void jiraOutageNeitherBlocksSavingNorCountsAsMissing() throws Exception {
        atlassian.down = true;
        var id = createMapping("jira-ausfall-" + shortId());

        var saved = save(id, List.of(rule("A", "DATA-8"), rule("B", "DATA-405")), "1.0.0");

        assertThat(codes(saved)).doesNotContain("REQUIREMENT_NOT_FOUND");
        atlassian.down = false;
        directory.refresh();
        assertThat(find(read("/api/mappings/" + id).path("issues"), "code", "REQUIREMENT_NOT_FOUND").path("message").asText())
                .contains("DATA-405");
    }

    @Test
    void publishCreatesLinkAndPageOnlyWhenTheWorkerRuns() throws Exception {
        var id = publish("veroeffentlicht-" + shortId(), List.of(rule("A", "DATA-1"), rule("B", "DATA-1")), "1.0.0");
        assertThat(atlassian.remoteLinks).isEmpty();

        worker.runDue();

        var link = atlassian.remoteLinks.get("DATA-1").get("datacontract:mapping:" + id);
        assertThat(link).isNotNull();
        assertThat(link.path("object").path("url").asText())
                .isEqualTo("https://datacontract.example.invalid/#/mappings/" + id);
        assertThat(link.path("object").path("title").asText()).contains("1.0.0");

        var page = atlassian.pageForSubject(id);
        assertThat(page.parentId).isEqualTo("999");
        assertThat(page.spaceId).isEqualTo("111");
        assertThat(page.body).contains("datacontract-id:" + id).contains("/browse/DATA-1");

        var tasks = read("/api/integrations/tasks?subjectId=" + id);
        assertThat(tasks.path("pageUrl").asText()).contains("/wiki/spaces/DATA/pages/");
        assertThat(tasks.path("tasks").findValuesAsText("state")).containsOnly("DONE");
    }

    @Test
    void republishUpdatesInsteadOfDuplicatingAndRemovesLinkOfDroppedRequirement() throws Exception {
        var name = "wiederholt-" + shortId();
        var id = publish(name, List.of(rule("A", "DATA-1"), rule("B", "DATA-2")), "1.0.0");
        worker.runDue();
        assertThat(atlassian.remoteLinks.get("DATA-2")).containsKey("datacontract:mapping:" + id);
        var pagesBefore = atlassian.pages.size();

        act("/api/mappings/" + id + "/reopen", anna());
        publishExisting(id, List.of(rule("A", "DATA-1"), rule("B", "DATA-3")), "1.1.0");
        worker.runDue();

        assertThat(atlassian.pages).hasSize(pagesBefore);
        var page = atlassian.pageForSubject(id);
        assertThat(page.version).isEqualTo(2);
        var globalId = "datacontract:mapping:" + id;
        assertThat(atlassian.remoteLinks.get("DATA-1").get(globalId).path("object").path("title").asText()).contains("1.1.0");
        assertThat(atlassian.remoteLinks.get("DATA-3")).containsKey(globalId);
        assertThat(atlassian.remoteLinks.getOrDefault("DATA-2", Map.of())).doesNotContainKey(globalId);
    }

    @Test
    void foreignPageWithTheSameTitleIsNeverOverwritten() throws Exception {
        var name = "fremd-" + shortId();
        atlassian.addForeignPage("Contract: " + name, "<p>Von Hand geschrieben</p>");

        var id = publish(name, List.of(rule("A", "DATA-1")), "1.0.0");
        worker.runDue();

        assertThat(atlassian.foreignPageBody("Contract: " + name)).isEqualTo("<p>Von Hand geschrieben</p>");
        var tasks = read("/api/integrations/tasks?subjectId=" + id).path("tasks");
        var page = find(tasks, "kind", "DOCUMENTATION_PAGE");
        assertThat(page.path("state").asText()).isEqualTo("PENDING");
        assertThat(page.path("lastError").asText()).contains("fremde Seite");
    }

    @Test
    void outageDoesNotAffectPublishAndErrorsNeverContainResponseBodiesOrSecrets() throws Exception {
        atlassian.failWith = 401;
        var id = publish("ausfall-" + shortId(), List.of(rule("A", "DATA-1")), "1.0.0");
        assertThat(read("/api/mappings/" + id).path("status").asText()).isEqualTo("PUBLISHED");

        worker.runDue();

        var tasks = read("/api/integrations/tasks?subjectId=" + id).path("tasks");
        assertThat(tasks.findValuesAsText("state")).containsOnly("PENDING");
        assertThat(tasks.findValuesAsText("lastError")).isNotEmpty().allSatisfy(error ->
                assertThat(error).contains("401").doesNotContain(SECRET).doesNotContain("geheime-antwort"));
    }

    @Test
    void userTextIsEscapedInConfluencePages() throws Exception {
        var hostile = "<script>alert(1)</script> & \"x\"\u0001";
        var name = "escape-" + shortId();
        var id = createMapping(name);
        var rule = rule("A", "DATA-1");
        rule.put("description", hostile);
        publishExisting(id, List.of(rule), "1.0.0");

        worker.runDue();

        var body = atlassian.pageForSubject(id).body;
        assertThat(body).doesNotContain("<script>").doesNotContain("\u0001")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;x&quot;");
    }

    @Test
    void traceConnectsRequirementWithRulesContractsAndProducts() throws Exception {
        var contract = publish("trace-" + shortId(), List.of(rule("A", "DATA-1"), rule("B", null)), "1.0.0");
        var created = send(post("/api/products"),
                Map.of("name", "Trace Produkt", "productName", "trace-produkt-" + shortId()), anna(), 201);
        var product = new HashMap<String, Object>();
        product.put("version", created.path("version").asLong());
        product.put("name", "Trace Produkt");
        product.put("productVersion", "1.0.0");
        product.put("domain", "sales");
        product.put("ownerTeam", "t");
        var port = new HashMap<String, Object>();
        port.put("direction", "OUTPUT");
        port.put("name", "out");
        port.put("contractMappingId", contract);
        port.put("contractVersion", "1.0.0");
        product.put("ports", List.of(port));
        send(put("/api/products/" + created.path("id").asText()), product, anna(), 200);

        var trace = read("/api/requirements");

        JsonNode entry = null;
        for (var candidate : trace.path("requirements")) {
            if ("DATA-1".equals(candidate.path("key").asText())) {
                entry = candidate;
            }
        }
        assertThat(entry).isNotNull();
        assertThat(entry.path("summary").asText()).isEqualTo("Kundenstamm bereitstellen");
        assertThat(entry.path("status").asText()).isEqualTo("In Arbeit");
        assertThat(entry.path("url").asText()).endsWith("/browse/DATA-1");
        assertThat(entry.path("rules").findValuesAsText("targetColumn")).contains("A");
        assertThat(entry.path("products").findValuesAsText("productTitle")).contains("Trace Produkt");
        assertThat(trace.path("rulesWithoutRequirement").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void onlyReviewersCanRetryFailedTasks() throws Exception {
        mvc.perform(post("/api/integrations/tasks/" + UUID.randomUUID() + "/retry").with(anna()).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/integrations/tasks/" + UUID.randomUUID() + "/retry").with(rene()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    private UUID publish(String name, List<Map<String, Object>> rules, String version) throws Exception {
        var id = createMapping(name);
        publishExisting(id, rules, version);
        return id;
    }

    private void publishExisting(UUID id, List<Map<String, Object>> rules, String version) throws Exception {
        save(id, rules, version);
        var inReview = act("/api/mappings/" + id + "/submit", anna());
        send(post("/api/mappings/" + id + "/approve"), Map.of("contentHash", inReview.path("contentHash").asText()), rene(), 200);
        act("/api/mappings/" + id + "/publish", rene());
    }

    private UUID createMapping(String name) throws Exception {
        var created = send(post("/api/mappings"), Map.of("name", name, "contractName", name), anna(), 201);
        return UUID.fromString(created.path("id").asText());
    }

    private JsonNode save(UUID id, List<Map<String, Object>> rules, String version) throws Exception {
        var current = read("/api/mappings/" + id);
        var body = new HashMap<String, Object>();
        body.put("version", current.path("version").asLong());
        body.put("name", current.path("name").asText());
        body.put("contractVersion", version);
        body.put("domain", "sales");
        body.put("targetTable", "ZIEL");
        body.put("ownerTeam", "data-team");
        body.put("rules", rules);
        return send(put("/api/mappings/" + id), body, anna(), 200);
    }

    private static Map<String, Object> rule(String column, String requirement) {
        var rule = new HashMap<String, Object>();
        rule.put("transformation", "Testregel");
        rule.put("targetColumn", column);
        rule.put("targetType", "VARCHAR(10)");
        rule.put("required", false);
        rule.put("primaryKey", false);
        rule.put("requirementKey", requirement);
        return rule;
    }

    private JsonNode act(String path, RequestPostProcessor actor) throws Exception {
        return send(post(path), null, actor, 200);
    }

    private JsonNode send(MockHttpServletRequestBuilder request, Object body, RequestPostProcessor actor, int expected)
            throws Exception {
        request.with(actor).with(csrf());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        var result = mvc.perform(request).andExpect(status().is(expected)).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode read(String path) throws Exception {
        var result = mvc.perform(get(path).with(anna())).andExpect(status().isOk()).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode find(JsonNode items, String field, String value) {
        for (var item : items) {
            if (value.equals(item.path(field).asText())) {
                return item;
            }
        }
        throw new AssertionError(value + " not found in " + items);
    }

    private static List<String> codes(JsonNode mapping) {
        return mapping.path("issues").findValuesAsText("code");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static final class FakeAtlassian {
        private static final Map<String, String> ISSUES = Map.of(
                "DATA-1", "Kundenstamm bereitstellen",
                "DATA-2", "Umsatz ergänzen",
                "DATA-3", "Reporting",
                "DATA-8", "Erst nach dem Ausfall geprüft");

        final Map<String, String> laterIssues = new ConcurrentHashMap<>();
        final List<String> authHeaders = new CopyOnWriteArrayList<>();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final Map<String, Map<String, JsonNode>> remoteLinks = new ConcurrentHashMap<>();
        final Map<String, Page> pages = new ConcurrentHashMap<>();
        volatile boolean down;
        volatile int failWith;
        private HttpServer server;
        private int nextPageId = 1000;

        static final class Page {
            String id;
            String title;
            String body;
            String spaceId;
            String parentId;
            int version;
        }

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        void stop() {
            server.stop(0);
        }

        int port() {
            return server.getAddress().getPort();
        }

        void reset() {
            authHeaders.clear();
            requests.clear();
            remoteLinks.clear();
            down = false;
            failWith = 0;
        }

        Page pageForSubject(UUID id) {
            return pages.values().stream()
                    .filter(page -> page.body.contains("datacontract-id:" + id))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no page for " + id));
        }

        void addForeignPage(String title, String body) {
            var page = new Page();
            page.id = "foreign-" + title;
            page.title = title;
            page.body = body;
            page.spaceId = "111";
            page.version = 1;
            pages.put(page.id, page);
        }

        String foreignPageBody(String title) {
            return pages.get("foreign-" + title).body;
        }

        private synchronized void handle(HttpExchange exchange) throws IOException {
            var method = exchange.getRequestMethod();
            var path = exchange.getRequestURI().getPath();
            var query = query(exchange.getRequestURI().getRawQuery());
            var raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(method + " " + path);
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if (down) {
                respond(exchange, 503, "{}");
                return;
            }
            if (failWith != 0) {
                respond(exchange, failWith, "{\"message\":\"geheime-antwort\"}");
                return;
            }
            var body = raw.isEmpty() ? null : JSON.readTree(raw);
            if (method.equals("GET") && path.equals("/rest/api/3/issue/picker")) {
                respond(exchange, 200, """
                        {"sections":[{"label":"History","issues":[{"key":"DATA-1","summaryText":"Kundenstamm bereitstellen"}]},
                        {"label":"Current","issues":[{"key":"DATA-1","summaryText":"Kundenstamm bereitstellen"}]}]}""");
            } else if (method.equals("POST") && path.equals("/rest/api/3/issue/bulkfetch")) {
                bulkFetch(exchange, body);
            } else if (path.matches("/rest/api/3/issue/[^/]+/remotelink")) {
                var key = path.split("/")[5];
                if (method.equals("POST")) {
                    if (!body.has("object") || !body.path("object").has("url") || !body.path("object").has("title")) {
                        respond(exchange, 400, "{}");
                        return;
                    }
                    remoteLinks.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).put(body.path("globalId").asText(), body);
                    respond(exchange, 201, "{\"id\":1,\"self\":\"x\"}");
                } else {
                    var links = remoteLinks.get(key);
                    if (links == null || links.remove(query.get("globalId")) == null) {
                        respond(exchange, 404, "{}");
                    } else {
                        respond(exchange, 204, "");
                    }
                }
            } else if (method.equals("GET") && path.equals("/wiki/api/v2/spaces")) {
                respond(exchange, 200, "DATA".equals(query.get("keys")) ? "{\"results\":[{\"id\":\"111\",\"key\":\"DATA\"}]}" : "{\"results\":[]}");
            } else if (method.equals("GET") && path.equals("/wiki/api/v2/pages")) {
                var match = pages.values().stream().filter(page -> page.title.equals(query.get("title"))).toList();
                var results = new ArrayList<Object>();
                match.forEach(page -> results.add(view(page)));
                respond(exchange, 200, JSON.writeValueAsString(Map.of("results", results)));
            } else if (method.equals("POST") && path.equals("/wiki/api/v2/pages")) {
                createPage(exchange, body);
            } else if (path.startsWith("/wiki/api/v2/pages/")) {
                pageById(exchange, method, path.substring("/wiki/api/v2/pages/".length()), body);
            } else {
                respond(exchange, 404, "{}");
            }
        }

        private void bulkFetch(HttpExchange exchange, JsonNode body) throws IOException {
            if (!body.path("fields").isArray() || body.path("issueIdsOrKeys").size() > 100) {
                respond(exchange, 400, "{}");
                return;
            }
            var issues = new ArrayList<Object>();
            for (var key : body.path("issueIdsOrKeys")) {
                var summary = ISSUES.getOrDefault(key.asText(), laterIssues.get(key.asText()));
                if (summary != null) {
                    issues.add(Map.of("key", key.asText(), "fields", Map.of("summary", summary,
                            "status", Map.of("name", "In Arbeit", "statusCategory", Map.of("key", "indeterminate")),
                            "issuetype", Map.of("name", "Story"))));
                }
            }
            respond(exchange, 200, JSON.writeValueAsString(Map.of("issues", issues, "issueErrors", List.of())));
        }

        private void createPage(HttpExchange exchange, JsonNode body) throws IOException {
            if (!body.has("spaceId") || !"storage".equals(body.path("body").path("representation").asText())) {
                respond(exchange, 400, "{}");
                return;
            }
            var page = new Page();
            page.id = String.valueOf(nextPageId++);
            page.title = body.path("title").asText();
            page.body = body.path("body").path("value").asText();
            page.spaceId = body.path("spaceId").asText();
            page.parentId = body.path("parentId").asText(null);
            page.version = 1;
            pages.put(page.id, page);
            respond(exchange, 200, JSON.writeValueAsString(view(page)));
        }

        private void pageById(HttpExchange exchange, String method, String id, JsonNode body) throws IOException {
            var page = pages.get(id);
            if (page == null) {
                respond(exchange, 404, "{}");
            } else if (method.equals("GET")) {
                respond(exchange, 200, JSON.writeValueAsString(view(page)));
            } else if (body.path("version").path("number").asInt() != page.version + 1 || !body.has("status")) {
                respond(exchange, 409, "{}");
            } else {
                page.title = body.path("title").asText();
                page.body = body.path("body").path("value").asText();
                page.version = body.path("version").path("number").asInt();
                respond(exchange, 200, JSON.writeValueAsString(view(page)));
            }
        }

        private static Map<String, Object> view(Page page) {
            var view = new LinkedHashMap<String, Object>();
            view.put("id", page.id);
            view.put("title", page.title);
            view.put("version", Map.of("number", page.version));
            view.put("body", Map.of("storage", Map.of("value", page.body)));
            view.put("_links", Map.of("webui", "/spaces/DATA/pages/" + page.id, "base", "http://site/wiki"));
            return view;
        }

        private static Map<String, String> query(String raw) {
            var values = new HashMap<String, String>();
            if (raw != null) {
                for (var part : raw.split("&")) {
                    var pair = part.split("=", 2);
                    values.put(pair[0], pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
                }
            }
            return values;
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        }
    }
}
