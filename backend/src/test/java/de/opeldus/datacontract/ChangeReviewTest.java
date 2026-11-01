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
import de.opeldus.datacontract.ddl.DdlIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
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

/** Change detection against the last published version, and the comparison with DDL read from a real Git repository. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChangeReviewTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path temp;

    private static Path ddlRepo;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        var artifacts = temp.resolve("artifacts.git");
        Git.init().setBare(true).setDirectory(artifacts.toFile()).setInitialBranch("main").call().close();
        ddlRepo = temp.resolve("ddl-source");
        try (var git = Git.init().setDirectory(ddlRepo.toFile()).setInitialBranch("main").call()) {
            commitDdl(git, "sql/V1__ziel.sql", """
                    CREATE TABLE DWH.CORE.PRUEF_ZIEL (
                        KUNDEN_NR VARCHAR(10) NOT NULL PRIMARY KEY,
                        UMSATZ DECIMAL(18,2),
                        NUR_DDL DATE
                    ) CLUSTER BY (KUNDEN_NR);
                    """);
        }
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:changes;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        registry.add("app.git.workdir", () -> temp.resolve("workspace").toString());
        registry.add("app.git.remote-url", () -> artifacts.toUri().toString());
        registry.add("app.ddl.remote-url", () -> ddlRepo.toUri().toString());
        registry.add("app.ddl.workdir", () -> temp.resolve("ddl-workspace").toString());
        registry.add("app.ddl.path", () -> "sql");
    }

    private static void commitDdl(Git git, String path, String content) throws Exception {
        var file = git.getRepository().getWorkTree().toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        git.add().addFilepattern(path).call();
        var person = new PersonIdent("Dev", "dev@example.invalid");
        git.commit().setMessage(path).setAuthor(person).setCommitter(person).setSign(false).call();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    DdlIndex ddlIndex;

    private static RequestPostProcessor anna() {
        return user("anna").roles("EDITOR");
    }

    private static RequestPostProcessor rene() {
        return user("rene").roles("REVIEWER");
    }

    @Test
    void neverPublishedContractHasNoBaseline() throws Exception {
        var id = createMapping("erstmal-" + shortId());

        var report = read("/api/mappings/" + id + "/changes");

        assertThat(report.path("baselineVersion").isNull()).isTrue();
        assertThat(report.path("changes")).isEmpty();
    }

    @Test
    void breakingChangesAreDetectedAndRequireANewMajorVersion() throws Exception {
        var id = published("inkompatibel-" + shortId(), List.of(
                rule(null, "A", "VARCHAR(20)", true, true),
                rule(null, "B", "VARCHAR(20)", true, false),
                rule(null, "C", "VARCHAR(20)", true, false),
                rule(null, "D", "VARCHAR(20)", true, false),
                rule(null, "E", "VARCHAR(20)", true, false)));
        var current = reopen(id);
        var rules = rules(current);
        rename(rules, "B", "B_NEU");
        setType(rules, "C", "VARCHAR(5)");
        setRequired(rules, "D", false);
        setType(rules, "E", "DATE");
        rules.add(rule(null, "F", "VARCHAR(20)", false, false));
        rules.removeIf(rule -> "A".equals(rule.get("targetColumn")));

        var updated = save(id, rules, "1.1.0");

        assertThat(codes(updated)).contains("CHANGE_NEEDS_MAJOR");
        var report = read("/api/mappings/" + id + "/changes");
        assertThat(report.path("baselineVersion").asText()).isEqualTo("1.0.0");
        assertThat(report.path("changes").findValuesAsText("code")).contains(
                "COLUMN_REMOVED", "COLUMN_RENAMED", "TYPE_NARROWED", "NO_LONGER_REQUIRED", "TYPE_INCOMPATIBLE",
                "COLUMN_ADDED");
        assertThat(report.path("changes").get(0).path("impact").asText()).isEqualTo("BREAKING");

        mvc.perform(post("/api/mappings/" + id + "/submit").with(anna()).with(csrf()))
                .andExpect(status().isConflict());

        var fixed = save(id, rules, "2.0.0");
        assertThat(codes(fixed)).doesNotContain("CHANGE_NEEDS_MAJOR");
    }

    @Test
    void compatibleChangesDoNotDemandMajorButContentChangesDoSuggestMinor() throws Exception {
        var id = published("verträglich-".replace("ä", "ae") + shortId(), List.of(
                rule(null, "A", "VARCHAR(10)", false, false),
                rule(null, "B", "VARCHAR(10)", false, false)));
        var rules = rules(reopen(id));
        setType(rules, "A", "VARCHAR(20)");
        rules.add(rule(null, "NEU", "DATE", false, false));

        var patch = save(id, rules, "1.0.1");
        assertThat(codes(patch)).contains("CHANGE_NEEDS_MINOR").doesNotContain("CHANGE_NEEDS_MAJOR");

        var minor = save(id, rules(patch), "1.1.0");
        assertThat(codes(minor)).doesNotContain("CHANGE_NEEDS_MINOR", "CHANGE_NEEDS_MAJOR");
        assertThat(read("/api/mappings/" + id + "/changes").path("changes").findValuesAsText("code"))
                .containsExactlyInAnyOrder("TYPE_WIDENED", "COLUMN_ADDED");
    }

    @Test
    void documentationChangesOnlyNeedAPatchVersion() throws Exception {
        var id = published("doku-" + shortId(), List.of(rule(null, "A", "VARCHAR(10)", false, false)));
        var rules = rules(reopen(id));
        rules.get(0).put("description", "Neue Beschreibung");
        rules.get(0).put("requirementKey", "DATA-99");

        var updated = save(id, rules, "1.0.1");

        assertThat(codes(updated)).doesNotContain("CHANGE_NEEDS_MINOR", "CHANGE_NEEDS_MAJOR");
        assertThat(read("/api/mappings/" + id + "/changes").path("changes").findValuesAsText("impact"))
                .containsOnly("DOCUMENTATION");
    }

    @Test
    void replacedRowWithTheSameColumnIsNotReportedAsRemoved() throws Exception {
        var id = published("ersetzt-" + shortId(), List.of(rule(null, "A", "VARCHAR(10)", false, false)));
        var current = reopen(id);

        save(id, List.of(rule(null, "A", "VARCHAR(10)", false, false)), current.path("contractVersion").asText());

        var codes = read("/api/mappings/" + id + "/changes").path("changes").findValuesAsText("code");
        assertThat(codes).doesNotContain("COLUMN_REMOVED", "COLUMN_ADDED");
    }

    @Test
    void breakingChangeNamesDirectAndTransitiveConsumers() throws Exception {
        var source = published("kette-a-" + shortId(), List.of(rule(null, "A", "VARCHAR(10)", false, false)));
        var middle = published("kette-b-" + shortId(), List.of(rule(null, "A", "VARCHAR(10)", false, false)));
        var last = published("kette-c-" + shortId(), List.of(rule(null, "A", "VARCHAR(10)", false, false)));
        var first = product("erste-" + shortId(), source, middle);
        var second = product("zweite-" + shortId(), middle, last);

        var rules = rules(reopen(source));
        setType(rules, "A", "DATE");
        var updated = save(source, rules, "2.0.0");

        assertThat(codes(updated)).contains("BREAKING_AFFECTS_CONSUMERS");
        var affected = read("/api/mappings/" + source + "/changes").path("affected");
        var names = new ArrayList<String>();
        affected.forEach(product -> names.add(product.path("productName").asText() + ":" + product.path("direct").asBoolean()));
        assertThat(names).containsExactly(first + ":true", second + ":false");
    }

    @Test
    void comparesTargetWithDdlFromGitWithoutADatabase() throws Exception {
        var created = createMapping("ddl-" + shortId());
        var rules = List.of(
                rule(null, "KUNDEN_NR", "VARCHAR(10)", true, true),
                rule(null, "UMSATZ", "VARCHAR(10)", false, false),
                rule(null, "FEHLT", "DATE", false, false));

        var updated = saveWithTable(created, rules, "1.0.0", "PRUEF_ZIEL");

        assertThat(codes(updated)).contains("DDL_TYPE_MISMATCH", "DDL_COLUMN_MISSING", "DDL_COLUMN_UNMAPPED");
        var unmapped = find(updated, "DDL_COLUMN_UNMAPPED");
        assertThat(unmapped.path("message").asText()).contains("NUR_DDL");
        assertThat(codes(updated)).doesNotContain("DDL_PRIMARY_KEY", "DDL_NOT_NULL", "DDL_TABLE_UNKNOWN");

        mvc.perform(post("/api/mappings/" + created + "/submit").with(anna()).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void unknownTableOnlyWarnsAndNewDdlCommitsAreRecognizedOnRefresh() throws Exception {
        var table = "SPAETER_" + shortId().toUpperCase().replace("-", "");
        var created = createMapping("spaeter-" + shortId());
        var rules = List.of(rule(null, "A", "VARCHAR(10)", false, false));

        var before = saveWithTable(created, rules, "1.0.0", table);
        assertThat(codes(before)).contains("DDL_TABLE_UNKNOWN");
        assertThat(errors(before)).isEmpty();

        try (var git = Git.open(ddlRepo.toFile())) {
            commitDdl(git, "sql/V9__" + table + ".sql", "CREATE TABLE " + table + " (A VARCHAR(10), B INT);");
        }
        ddlIndex.refresh();

        var after = read("/api/mappings/" + created);
        assertThat(codes(after)).doesNotContain("DDL_TABLE_UNKNOWN").contains("DDL_COLUMN_UNMAPPED");
        var status = read("/api/ddl/status");
        assertThat(status.path("configured").asBoolean()).isTrue();
        assertThat(status.path("tableCount").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(status.path("revision").asText()).hasSize(40);
    }

    private String product(String name, UUID input, UUID output) throws Exception {
        var created = send(post("/api/products"), Map.of("name", name, "productName", name), anna(), 200 + 1);
        var body = new HashMap<String, Object>();
        body.put("version", created.path("version").asLong());
        body.put("name", name);
        body.put("productVersion", "1.0.0");
        body.put("domain", "sales");
        body.put("ownerTeam", "team");
        body.put("ports", List.of(port("INPUT", input), port("OUTPUT", output)));
        send(put("/api/products/" + created.path("id").asText()), body, anna(), 200);
        return name;
    }

    private static Map<String, Object> port(String direction, UUID contract) {
        var port = new HashMap<String, Object>();
        port.put("direction", direction);
        port.put("name", direction.toLowerCase());
        port.put("contractMappingId", contract);
        port.put("contractVersion", "1.0.0");
        return port;
    }

    private UUID published(String name, List<Map<String, Object>> rules) throws Exception {
        var id = createMapping(name);
        save(id, rules, "1.0.0");
        var inReview = send(post("/api/mappings/" + id + "/submit"), null, anna(), 200);
        send(post("/api/mappings/" + id + "/approve"), Map.of("contentHash", inReview.path("contentHash").asText()), rene(), 200);
        send(post("/api/mappings/" + id + "/publish"), null, rene(), 200);
        return id;
    }

    private JsonNode reopen(UUID id) throws Exception {
        return send(post("/api/mappings/" + id + "/reopen"), null, anna(), 200);
    }

    private UUID createMapping(String name) throws Exception {
        var created = send(post("/api/mappings"), Map.of("name", name, "contractName", name), anna(), 201);
        return UUID.fromString(created.path("id").asText());
    }

    private JsonNode save(UUID id, List<Map<String, Object>> rules, String version) throws Exception {
        return saveWithTable(id, rules, version, "ZIEL_OHNE_DDL");
    }

    private JsonNode saveWithTable(UUID id, List<Map<String, Object>> rules, String version, String table) throws Exception {
        var current = read("/api/mappings/" + id);
        var body = new HashMap<String, Object>();
        body.put("version", current.path("version").asLong());
        body.put("name", current.path("name").asText());
        body.put("contractVersion", version);
        body.put("domain", "sales");
        body.put("targetTable", table);
        body.put("ownerTeam", "data-team");
        body.put("rules", rules);
        return send(put("/api/mappings/" + id), body, anna(), 200);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rules(JsonNode mapping) {
        var rules = new ArrayList<Map<String, Object>>();
        mapping.path("rules").forEach(rule -> rules.add(JSON.convertValue(rule, HashMap.class)));
        return rules;
    }

    private static void rename(List<Map<String, Object>> rules, String from, String to) {
        rules.stream().filter(rule -> from.equals(rule.get("targetColumn"))).forEach(rule -> rule.put("targetColumn", to));
    }

    private static void setType(List<Map<String, Object>> rules, String column, String type) {
        rules.stream().filter(rule -> column.equals(rule.get("targetColumn"))).forEach(rule -> rule.put("targetType", type));
    }

    private static void setRequired(List<Map<String, Object>> rules, String column, boolean required) {
        rules.stream().filter(rule -> column.equals(rule.get("targetColumn"))).forEach(rule -> rule.put("required", required));
    }

    private static Map<String, Object> rule(UUID id, String column, String type, boolean required, boolean primaryKey) {
        var rule = new HashMap<String, Object>();
        rule.put("id", id);
        rule.put("transformation", "Testregel");
        rule.put("targetColumn", column);
        rule.put("targetType", type);
        rule.put("required", required);
        rule.put("primaryKey", primaryKey);
        rule.put("requirementKey", "DATA-1");
        return rule;
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

    private static JsonNode find(JsonNode mapping, String code) {
        for (var issue : mapping.path("issues")) {
            if (code.equals(issue.path("code").asText())) {
                return issue;
            }
        }
        throw new AssertionError(code + " not found");
    }

    private static List<String> codes(JsonNode mapping) {
        return mapping.path("issues").findValuesAsText("code");
    }

    private static List<String> errors(JsonNode mapping) {
        var errors = new ArrayList<String>();
        mapping.path("issues").forEach(issue -> {
            if ("ERROR".equals(issue.path("severity").asText())) {
                errors.add(issue.path("code").asText());
            }
        });
        return errors;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
