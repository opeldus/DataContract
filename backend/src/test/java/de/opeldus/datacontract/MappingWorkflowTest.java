package de.opeldus.datacontract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MappingWorkflowTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path temp;

    private static Path remote;

    @DynamicPropertySource
    static void gitProperties(DynamicPropertyRegistry registry) throws Exception {
        remote = temp.resolve("remote.git");
        Git.init().setBare(true).setDirectory(remote.toFile()).setInitialBranch("main").call().close();
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

    private static RequestPostProcessor both() {
        return user("both").roles("EDITOR", "REVIEWER");
    }

    @Test
    void draftIsReviewedApprovedAndPublishedToGitWithPersonAsAuthor() throws Exception {
        var id = createValidMapping("kunde-" + shortId(), anna());

        var inReview = act(id, "submit", anna());
        assertThat(inReview.path("status").asText()).isEqualTo("IN_REVIEW");

        var approved = postJson(id, "approve", Map.of("contentHash", inReview.path("contentHash").asText()), rene());
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");

        var published = act(id, "publish", rene());
        assertThat(codes(published)).doesNotContain("VERSION_NOT_INCREASED");
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.path("publishedVersion").asText()).isEqualTo("1.0.0");

        var contractName = published.path("contractName").asText();
        try (var git = Git.open(remote.toFile()); var walk = new RevWalk(git.getRepository())) {
            var head = walk.parseCommit(git.getRepository().resolve("refs/heads/main"));
            assertThat(head.getAuthorIdent().getName()).isEqualTo("Anna Autor");
            assertThat(head.getAuthorIdent().getEmailAddress()).isEqualTo("anna@example.invalid");
            assertThat(head.getCommitterIdent().getName()).isEqualTo("DataContract Bot");
            assertThat(head.getFullMessage()).contains("Approved-by: rene").contains(contractName);
            assertThat(head.getName()).isEqualTo(published.path("publishedCommit").asText());
        }
    }

    @Test
    void personWhoWorkedOnMappingCannotApproveIt() throws Exception {
        var id = createValidMapping("vier-augen-" + shortId(), both());
        var inReview = act(id, "submit", both());

        mvc.perform(post("/api/mappings/" + id + "/approve").with(both()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("contentHash", inReview.path("contentHash").asText()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvalWithOutdatedHashIsRejected() throws Exception {
        var id = createValidMapping("hash-" + shortId(), anna());
        act(id, "submit", anna());

        mvc.perform(post("/api/mappings/" + id + "/approve").with(rene()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentHash\":\"veraltet\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void editorCannotApproveAndMappingIsLockedDuringReview() throws Exception {
        var id = createValidMapping("rollen-" + shortId(), anna());
        var inReview = act(id, "submit", anna());

        mvc.perform(post("/api/mappings/" + id + "/approve").with(anna()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("contentHash", inReview.path("contentHash").asText()))))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/mappings/" + id).with(anna()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(updateBody(inReview.path("version").asLong(), validRules()))))
                .andExpect(status().isConflict());
    }

    @Test
    void submitIsBlockedWhileValidationErrorsExist() throws Exception {
        var created = create("fehler-" + shortId(), anna());
        var id = UUID.fromString(created.path("id").asText());

        mvc.perform(post("/api/mappings/" + id + "/submit").with(anna()).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void validationUsesCatalogAndFlagsDuplicatesAndUnknownColumns() throws Exception {
        var created = create("pruefung-" + shortId(), anna());
        var rules = List.of(
                rule("db2.SALES.KUNDE", "GIBT_ES_NICHT", "KUNDE", "VARCHAR(10)", null),
                rule("db2.SALES.KUNDE", "NAME", "kunde", "VARCHAR(100)", null),
                rule("db2.SALES.KUNDE", "UMSATZ", "UMSATZ", "DATE", null));
        var updated = update(created, rules);

        assertThat(codes(updated)).contains("SOURCE_COLUMN_UNKNOWN", "TARGET_DUPLICATE", "TYPE_MISMATCH");
    }

    @Test
    void ruleIdsStayStableAcrossEdits() throws Exception {
        var created = create("stabil-" + shortId(), anna());
        var first = update(created, validRules());
        var firstId = first.path("rules").get(0).path("id").asText();

        var rules = JSON.convertValue(first.path("rules"), List.class);
        var second = update(first, rules);

        assertThat(second.path("rules").get(0).path("id").asText()).isEqualTo(firstId);
    }

    @Test
    void concurrentEditWithStaleVersionIsRejected() throws Exception {
        var created = create("parallel-" + shortId(), anna());
        update(created, validRules());

        mvc.perform(put("/api/mappings/" + created.path("id").asText()).with(anna()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(updateBody(created.path("version").asLong(), validRules()))))
                .andExpect(status().isConflict());
    }

    @Test
    void excelImportDetectsGermanHeadersBelowATitleRow() throws Exception {
        var created = create("excel-" + shortId(), anna());
        var file = new MockMultipartFile("file", "mapping.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, workbook());

        mvc.perform(multipart("/api/mappings/" + created.path("id").asText() + "/import").file(file)
                        .with(anna()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skippedRows").value(1))
                .andExpect(jsonPath("$.ignoredColumns[0]").value("Kommentar intern"))
                .andExpect(jsonPath("$.mapping.rules[0].targetColumn").value("KUNDEN_NR"))
                .andExpect(jsonPath("$.mapping.rules[0].required").value(true))
                .andExpect(jsonPath("$.mapping.rules[0].primaryKey").value(true))
                .andExpect(jsonPath("$.mapping.rules[0].requirementKey").value("DATA-1"));
    }

    @Test
    void invalidExcelFileIsRejectedWithBadRequest() throws Exception {
        var created = create("kaputt-" + shortId(), anna());
        var file = new MockMultipartFile("file", "x.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[] {1, 2});

        mvc.perform(multipart("/api/mappings/" + created.path("id").asText() + "/import").file(file)
                        .with(anna()).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publishedMappingRequiresHigherVersionForNextRelease() throws Exception {
        var id = createValidMapping("version-" + shortId(), anna());
        var inReview = act(id, "submit", anna());
        postJson(id, "approve", Map.of("contentHash", inReview.path("contentHash").asText()), rene());
        act(id, "publish", rene());

        var reopened = act(id, "reopen", anna());

        assertThat(reopened.path("status").asText()).isEqualTo("DRAFT");
        assertThat(codes(reopened)).contains("VERSION_NOT_INCREASED");
    }

    @Test
    void catalogAutocompleteSearchesIndexCaseInsensitivelyAndTreatsWildcardsLiterally() throws Exception {
        mvc.perform(get("/api/catalog/datasets").param("q", "sales.kunde").with(anna()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("db2.SALES.KUNDE"));
        mvc.perform(get("/api/catalog/datasets").param("q", "%").with(anna()))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/catalog/columns").param("dataset", "db2.SALES.KUNDE").param("q", "umsatz").with(anna()))
                .andExpect(jsonPath("$[0].name").value("UMSATZ"))
                .andExpect(jsonPath("$[0].nativeType").value("DECIMAL(18,2)"));
    }

    @Test
    void anonymousRequestsAreRejectedAndWritesNeedCsrfToken() throws Exception {
        mvc.perform(get("/api/mappings")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/mappings").with(anna())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"contractName\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void contractNameThatCouldEscapeTheRepositoryIsRejected() throws Exception {
        mvc.perform(post("/api/mappings").with(anna()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"contractName\":\"../../etc\"}"))
                .andExpect(status().isBadRequest());
    }

    private UUID createValidMapping(String contractName, RequestPostProcessor actor) throws Exception {
        var created = create(contractName, actor);
        var result = mvc.perform(put("/api/mappings/" + created.path("id").asText()).with(actor).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(updateBody(created.path("version").asLong(), validRules()))))
                .andExpect(status().isOk())
                .andReturn();
        var updated = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(errorCodes(updated)).isEmpty();
        return UUID.fromString(updated.path("id").asText());
    }

    private JsonNode create(String contractName, RequestPostProcessor actor) throws Exception {
        var result = mvc.perform(post("/api/mappings").with(actor).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("name", "Kunden", "contractName", contractName))))
                .andExpect(status().isCreated())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode update(JsonNode current, List<?> rules) throws Exception {
        var result = mvc.perform(put("/api/mappings/" + current.path("id").asText()).with(anna()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(updateBody(current.path("version").asLong(), rules))))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode act(UUID id, String action, RequestPostProcessor actor) throws Exception {
        var result = mvc.perform(post("/api/mappings/" + id + "/" + action).with(actor).with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postJson(UUID id, String action, Map<String, ?> body, RequestPostProcessor actor)
            throws Exception {
        var result = mvc.perform(post("/api/mappings/" + id + "/" + action).with(actor).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static Map<String, Object> updateBody(long version, List<?> rules) {
        return Map.of("version", version, "name", "Kunden", "contractVersion", "1.0.0", "domain", "sales",
                "description", "Kundenstamm", "targetTable", "DIM_KUNDE", "ownerTeam", "data-team", "rules", rules);
    }

    private static List<Map<String, Object>> validRules() {
        return List.of(
                rule("db2.SALES.KUNDE", "KUNDEN_NR", "KUNDEN_NR", "VARCHAR(10)", "DATA-1"),
                rule("db2.SALES.KUNDE", "UMSATZ", "UMSATZ_EUR", "DECIMAL(18,2)", "DATA-2"));
    }

    private static Map<String, Object> rule(String dataset, String column, String target, String type,
            String requirement) {
        var rule = new java.util.HashMap<String, Object>();
        rule.put("sourceDataset", dataset);
        rule.put("sourceColumn", column);
        rule.put("targetColumn", target);
        rule.put("targetType", type);
        rule.put("required", false);
        rule.put("primaryKey", false);
        rule.put("requirementKey", requirement);
        return rule;
    }

    private static List<String> codes(JsonNode mapping) {
        return mapping.path("issues").findValuesAsText("code");
    }

    private static List<String> errorCodes(JsonNode mapping) {
        var codes = new java.util.ArrayList<String>();
        mapping.path("issues").forEach(issue -> {
            if ("ERROR".equals(issue.path("severity").asText())) {
                codes.add(issue.path("code").asText() + ": " + issue.path("message").asText());
            }
        });
        return codes;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static byte[] workbook() throws IOException {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Mapping");
            sheet.createRow(0).createCell(0).setCellValue("Mapping Kundenstamm");
            var header = sheet.createRow(2);
            var titles = List.of("Quelltabelle", "Quellfeld", "Zielfeld", "Datentyp", "Pflicht", "PK", "Anforderung",
                    "Kommentar intern");
            for (int i = 0; i < titles.size(); i++) {
                header.createCell(i).setCellValue(titles.get(i));
            }
            writeRow(sheet.createRow(3), "db2.SALES.KUNDE", "KUNDEN_NR", "KUNDEN_NR", "VARCHAR(10)", "x", "ja", "DATA-1");
            writeRow(sheet.createRow(4), "db2.SALES.KUNDE", "NAME", "NAME", "VARCHAR(100)", "", "", "");
            writeRow(sheet.createRow(5), "db2.SALES.KUNDE", "NAME", "", "", "", "", "");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private static void writeRow(org.apache.poi.ss.usermodel.Row row, String... values) {
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }
}
