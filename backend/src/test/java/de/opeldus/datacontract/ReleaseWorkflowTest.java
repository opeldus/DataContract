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
import de.opeldus.datacontract.integration.IntegrationTaskWorker;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReleaseWorkflowTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path temp;

    private static Path remote;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        remote = temp.resolve("remote.git");
        Git.init().setBare(true).setDirectory(remote.toFile()).setInitialBranch("main").call().close();
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:releases;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        registry.add("app.git.workdir", () -> temp.resolve("workspace").toString());
        registry.add("app.git.remote-url", () -> remote.toUri().toString());
        registry.add("app.atlassian.task-interval", () -> "PT1H");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    IntegrationTaskWorker worker;

    private static RequestPostProcessor anna() {
        return user("anna").roles("EDITOR");
    }

    private static RequestPostProcessor rene() {
        return user("rene").roles("REVIEWER");
    }

    @Test
    void publishingInAReleaseStaysOnItsBranchAndLeavesTheMainlineAlone() throws Exception {
        var name = "kunde-" + shortId();
        var main = publishNew(name, null, List.of(rule("A", "VARCHAR(10)")), "1.0.0");
        var release = createRelease("r-" + shortId(), null);

        var forkId = fork(release, main);
        var rules = rules(read("/api/mappings/" + forkId));
        rules.add(rule("NEU", "DATE"));
        publishExisting(forkId, rules, "1.1.0");

        assertThat(fileOnBranch(branch(release), "contracts/" + name + "/odcs.yaml")).contains("version: 1.1.0", "NEU");
        assertThat(fileOnBranch("main", "contracts/" + name + "/odcs.yaml")).contains("version: 1.0.0").doesNotContain("NEU");

        var mainRow = read("/api/mappings/" + main);
        assertThat(mainRow.path("contractVersion").asText()).isEqualTo("1.0.0");
        assertThat(mainRow.path("releaseId").isNull()).isTrue();
        var forkRow = read("/api/mappings/" + forkId);
        assertThat(forkRow.path("key").asText()).isEqualTo(main.toString());
        assertThat(forkRow.path("releaseName").asText()).isEqualTo(release.path("name").asText());
    }

    @Test
    void forkedRuleKeepsItsIdentityForChangeDetection() throws Exception {
        var main = publishNew("identitaet-" + shortId(), null, List.of(rule("ALT", "VARCHAR(10)")), "1.0.0");
        var release = createRelease("r-" + shortId(), null);
        var forkId = fork(release, main);

        var rules = rules(read("/api/mappings/" + forkId));
        rules.get(0).put("targetColumn", "UMBENANNT");
        save(forkId, rules, "2.0.0");

        var changes = read("/api/mappings/" + forkId + "/changes").path("changes");
        assertThat(changes.findValuesAsText("code")).containsExactly("COLUMN_RENAMED");
    }

    @Test
    void listShowsInheritedStatesUntilAReleaseTakesThemOver() throws Exception {
        var main = publishNew("erbt-" + shortId(), null, List.of(rule("A", "VARCHAR(10)")), "1.0.0");
        var release = createRelease("r-" + shortId(), null);

        var before = findBy(read("/api/mappings?release=" + id(release)), "key", main.toString());
        assertThat(before.path("inherited").asBoolean()).isTrue();
        assertThat(before.path("id").asText()).isEqualTo(main.toString());

        var forkId = fork(release, main);

        var after = findBy(read("/api/mappings?release=" + id(release)), "key", main.toString());
        assertThat(after.path("inherited").asBoolean()).isFalse();
        assertThat(after.path("id").asText()).isEqualTo(forkId.toString());
        var onMain = findBy(read("/api/mappings"), "key", main.toString());
        assertThat(onMain.path("id").asText()).isEqualTo(main.toString());
    }

    @Test
    void onlyPublishedStatesCanBeTakenOverAndOnlyOnce() throws Exception {
        var draft = createMapping("entwurf-" + shortId(), null);
        var main = publishNew("einmal-" + shortId(), null, List.of(rule("A", "VARCHAR(10)")), "1.0.0");
        var release = createRelease("r-" + shortId(), null);

        send(post("/api/releases/" + id(release) + "/mappings/" + draft + "/fork"), null, anna(), 409);
        fork(release, main);
        send(post("/api/releases/" + id(release) + "/mappings/" + main + "/fork"), null, anna(), 409);
    }

    @Test
    void childSeesVersionsOfItsBaseButNotOfASiblingRelease() throws Exception {
        var contract = publishNew("sicht-" + shortId(), null, List.of(rule("A", "VARCHAR(10)")), "1.0.0");
        var output = publishNew("sicht-out-" + shortId(), null, List.of(rule("A", "VARCHAR(10)")), "1.0.0");
        var base = createRelease("r-" + shortId(), null);
        var sibling = createRelease("r-" + shortId(), null);
        var child = createRelease("r-" + shortId(), id(base));
        publishExisting(fork(base, contract), rulesOf(contract), "1.1.0");

        var inChild = product(id(child), List.of(port("INPUT", contract, "1.1.0"), port("OUTPUT", output, "1.0.0")));
        assertThat(codes(inChild)).doesNotContain("CONTRACT_VERSION_UNKNOWN");

        var inSibling = product(id(sibling), List.of(port("INPUT", contract, "1.1.0"), port("OUTPUT", output, "1.0.0")));
        assertThat(codes(inSibling)).contains("CONTRACT_VERSION_UNKNOWN");
    }

    @Test
    void newVersionMustBeHigherThanVisibleAndUnusedAcrossReleases() throws Exception {
        var main = publishNew("nummer-" + shortId(), null, List.of(rule("A", "VARCHAR(10)")), "1.0.0");
        var first = createRelease("r-" + shortId(), null);
        var second = createRelease("r-" + shortId(), null);
        publishExisting(fork(first, main), rulesOf(main), "1.1.0");
        var forkInSecond = fork(second, main);

        var unchanged = save(forkInSecond, rulesOf(main), "1.0.0");
        assertThat(codes(unchanged)).contains("VERSION_NOT_INCREASED");

        var taken = save(forkInSecond, rulesOf(main), "1.1.0");
        assertThat(codes(taken)).contains("VERSION_TAKEN");

        var free = save(forkInSecond, rulesOf(main), "1.2.0");
        assertThat(codes(free)).doesNotContain("VERSION_NOT_INCREASED", "VERSION_TAKEN");
    }

    @Test
    void publishOnMainReachesDependentBranchesOfReleasesWithoutOwnCopy() throws Exception {
        var name = "nachfuehren-" + shortId();
        var main = createMapping(name, null);
        var release = createRelease("r-" + shortId(), null);
        var child = createRelease("r-" + shortId(), id(release));

        publishExisting(main, List.of(rule("A", "VARCHAR(10)")), "1.0.0");
        assertThat(fileOnBranch(branch(child), "contracts/" + name + "/odcs.yaml")).isNull();

        worker.runDue();

        assertThat(fileOnBranch(branch(release), "contracts/" + name + "/odcs.yaml")).contains("version: 1.0.0");
        assertThat(fileOnBranch(branch(child), "contracts/" + name + "/odcs.yaml")).contains("version: 1.0.0");
        var tasks = read("/api/integrations/tasks?subjectId=" + main).path("tasks");
        assertThat(tasks.findValuesAsText("kind")).containsOnly("GIT_FORWARD");
        assertThat(tasks.findValuesAsText("state")).containsOnly("DONE");
    }

    @Test
    void nonConflictingUpstreamChangeIsMergedAndForcesANewReview() throws Exception {
        var name = "merge-" + shortId();
        var main = publishNew(name, null, List.of(rule("A", "VARCHAR(10)"), rule("B", "VARCHAR(10)")), "1.0.0");
        var release = createRelease("r-" + shortId(), null);
        var forkId = fork(release, main);

        var forkRules = rules(read("/api/mappings/" + forkId));
        setField(forkRules, "A", "description", "Aus dem Release");
        forkRules.add(rule("NUR_RELEASE", "DATE"));
        publishExisting(forkId, forkRules, "2.0.0");

        var mainRules = rules(reopen(main));
        setField(mainRules, "B", "targetType", "VARCHAR(20)");
        publishExisting(main, mainRules, "1.1.0");

        var merged = read("/api/mappings/" + forkId);
        assertThat(merged.path("status").asText()).isEqualTo("DRAFT");
        assertThat(merged.path("conflicts")).isEmpty();
        assertThat(merged.path("publishedVersion").asText()).isEqualTo("2.0.0");
        assertThat(ruleBy(merged, "A").path("description").asText()).isEqualTo("Aus dem Release");
        assertThat(ruleBy(merged, "B").path("targetType").asText()).isEqualTo("VARCHAR(20)");
        assertThat(ruleBy(merged, "NUR_RELEASE").path("targetType").asText()).isEqualTo("DATE");
        assertThat(eventTypes(merged)).contains("FORWARD_MERGED");
    }

    @Test
    void secondUpstreamChangeMergesAgainstTheLastTakenOverState() throws Exception {
        var main = publishNew("zweimal-" + shortId(), null, List.of(rule("A", "VARCHAR(10)"), rule("B", "VARCHAR(10)")), "1.0.0");
        var release = createRelease("r-" + shortId(), null);
        var forkId = fork(release, main);
        var forkRules = rules(read("/api/mappings/" + forkId));
        setField(forkRules, "A", "description", "Aus dem Release");
        save(forkId, forkRules, "3.0.0");

        var first = rules(reopen(main));
        setField(first, "A", "targetType", "VARCHAR(20)");
        publishExisting(main, first, "1.1.0");
        var second = rules(reopen(main));
        setField(second, "A", "targetType", "VARCHAR(30)");
        publishExisting(main, second, "1.2.0");

        var merged = read("/api/mappings/" + forkId);
        assertThat(merged.path("conflicts")).isEmpty();
        assertThat(ruleBy(merged, "A").path("targetType").asText()).isEqualTo("VARCHAR(30)");
        assertThat(ruleBy(merged, "A").path("description").asText()).isEqualTo("Aus dem Release");
    }

    @Test
    void forwardMergeDoesNotCountAsAuthorship() throws Exception {
        var main = publishNew("autor-" + shortId(), null, List.of(rule("A", "VARCHAR(10)")), "1.0.0");
        var release = createRelease("r-" + shortId(), null);
        var forkId = fork(release, main);
        var rules = rules(read("/api/mappings/" + forkId));
        setField(rules, "A", "description", "x");
        publishExisting(forkId, rules, "2.0.0");

        var mainRules = rules(reopen(main));
        setField(mainRules, "A", "targetType", "VARCHAR(20)");
        publishExisting(main, mainRules, "1.1.0");

        var merged = read("/api/mappings/" + forkId);
        var inReview = save(forkId, rules(merged), "3.0.0");
        assertThat(errors(inReview)).isEmpty();
        var submitted = send(post("/api/mappings/" + forkId + "/submit"), null, anna(), 200);
        send(post("/api/mappings/" + forkId + "/approve"), Map.of("contentHash", submitted.path("contentHash").asText()), rene(), 200);
    }

    @Test
    void conflictBlocksSubmitUntilResolvedAndCanTakeEitherSide() throws Exception {
        var main = publishNew("konflikt-" + shortId(), null, List.of(rule("A", "VARCHAR(10)"), rule("B", "VARCHAR(10)")), "1.0.0");
        var release = createRelease("r-" + shortId(), null);
        var forkId = fork(release, main);
        var forkRules = rules(read("/api/mappings/" + forkId));
        setField(forkRules, "A", "targetType", "VARCHAR(30)");
        setField(forkRules, "B", "targetType", "VARCHAR(30)");
        save(forkId, forkRules, "2.0.0");

        var mainRules = rules(reopen(main));
        setField(mainRules, "A", "targetType", "VARCHAR(40)");
        setField(mainRules, "B", "targetType", "VARCHAR(40)");
        publishExisting(main, mainRules, "1.1.0");

        var conflicted = read("/api/mappings/" + forkId);
        assertThat(conflicted.path("conflicts")).hasSize(2);
        assertThat(codes(conflicted)).contains("MERGE_CONFLICT");
        assertThat(conflicted.path("conflicts").get(0).path("label").asText()).contains("Zieltyp");
        send(post("/api/mappings/" + forkId + "/submit"), null, anna(), 409);

        var first = conflicted.path("conflicts").get(0);
        var firstColumn = first.path("label").asText().split(":")[0];
        var afterTheirs = resolve(forkId, first.path("id").asText(), true);
        assertThat(ruleBy(afterTheirs, firstColumn).path("targetType").asText()).isEqualTo("VARCHAR(40)");
        assertThat(afterTheirs.path("conflicts")).hasSize(1);

        var second = afterTheirs.path("conflicts").get(0);
        var secondColumn = second.path("label").asText().split(":")[0];
        var afterOurs = resolve(forkId, second.path("id").asText(), false);
        assertThat(ruleBy(afterOurs, secondColumn).path("targetType").asText()).isEqualTo("VARCHAR(30)");
        assertThat(afterOurs.path("conflicts")).isEmpty();
        assertThat(codes(afterOurs)).doesNotContain("MERGE_CONFLICT");
    }

    @Test
    void ruleRemovedUpstreamButChangedHereCanBeResolvedByRemovingIt() throws Exception {
        var main = publishNew("entfernt-" + shortId(), null, List.of(rule("A", "VARCHAR(10)"), rule("B", "VARCHAR(10)")), "1.0.0");
        var release = createRelease("r-" + shortId(), null);
        var forkId = fork(release, main);
        var forkRules = rules(read("/api/mappings/" + forkId));
        setField(forkRules, "B", "description", "Geändert");
        save(forkId, forkRules, "2.0.0");

        var mainRules = rules(reopen(main));
        mainRules.removeIf(rule -> "B".equals(rule.get("targetColumn")));
        publishExisting(main, mainRules, "2.1.0");

        var conflicted = read("/api/mappings/" + forkId);
        var conflict = conflicted.path("conflicts").get(0);
        assertThat(conflict.path("removedUpstream").asBoolean()).isTrue();
        assertThat(conflict.path("label").asText()).isEqualTo("B");

        var resolved = resolve(forkId, conflict.path("id").asText(), true);
        assertThat(resolved.path("rules").findValuesAsText("targetColumn")).containsExactly("A");
    }

    @Test
    void conflictOfAnotherItemCannotBeResolvedThroughTheWrongPath() throws Exception {
        var main = publishNew("fremd-" + shortId(), null, List.of(rule("A", "VARCHAR(10)")), "1.0.0");

        send(post("/api/mappings/" + main + "/conflicts/" + UUID.randomUUID() + "/resolve"), Map.of("takeTheirs", true), anna(), 404);
    }

    @Test
    void autocompleteAndValidationKnowColumnsPlannedInTheRelease() throws Exception {
        var table = "PLAN_" + shortId().toUpperCase();
        var release = createRelease("r-" + shortId(), null);
        var planner = createMapping("plan-" + shortId(), id(release));
        saveWithTable(planner, List.of(rule("GEPLANT", "DATE")), "1.0.0", table);

        var tables = read("/api/catalog/datasets?q=" + table + "&release=" + id(release));
        assertThat(tables.get(0).path("name").asText()).isEqualTo(table);
        assertThat(tables.get(0).path("plannedIn").asText()).startsWith("plan-");
        assertThat(read("/api/catalog/datasets?q=" + table)).isEmpty();
        assertThat(read("/api/catalog/columns?dataset=" + table + "&release=" + id(release)).get(0).path("name").asText())
                .isEqualTo("GEPLANT");

        var consumer = createMapping("nutzer-" + shortId(), id(release));
        var known = sourced(table, "GEPLANT", "ZIEL");
        var updated = saveWithTable(consumer, List.of(known), "1.0.0", "ZIEL_" + shortId());
        assertThat(codes(updated)).contains("SOURCE_PLANNED").doesNotContain("SOURCE_DATASET_UNKNOWN", "SOURCE_COLUMN_UNKNOWN");

        var unknown = saveWithTable(consumer, List.of(sourced(table, "TIPPFEHLER", "ZIEL")), "1.0.0", "ZIEL_" + shortId());
        assertThat(codes(unknown)).contains("SOURCE_COLUMN_UNKNOWN");
    }

    @Test
    void productMayPlanOnContractOfItsReleaseButNotPublishBeforeIt() throws Exception {
        var release = createRelease("r-" + shortId(), null);
        var contract = createMapping("plan-contract-" + shortId(), id(release));
        save(contract, List.of(rule("A", "VARCHAR(10)")), "1.0.0");

        var product = product(id(release), List.of(port("OUTPUT", contract, "1.0.0")));
        assertThat(codes(product)).contains("CONTRACT_PLANNED");
        assertThat(errors(product)).isEmpty();

        var productId = product.path("id").asText();
        var submitted = send(post("/api/products/" + productId + "/submit"), null, anna(), 200);
        send(post("/api/products/" + productId + "/approve"), Map.of("contentHash", submitted.path("contentHash").asText()), rene(), 200);
        send(post("/api/products/" + productId + "/publish"), null, rene(), 409);

        publishExisting(contract, List.of(rule("A", "VARCHAR(10)")), "1.0.0");

        var published = send(post("/api/products/" + productId + "/publish"), null, rene(), 200);
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(fileOnBranch(branch(release), "products/" + published.path("productName").asText() + "/odps.yaml"))
                .contains("contractId");
    }

    @Test
    void planningOnAnUnpublishedContractIsNotAllowedOnTheMainline() throws Exception {
        var contract = createMapping("kein-plan-" + shortId(), null);
        save(contract, List.of(rule("A", "VARCHAR(10)")), "1.0.0");

        var product = product(null, List.of(port("OUTPUT", contract, "1.0.0")));

        assertThat(codes(product)).contains("CONTRACT_NOT_PUBLISHED").doesNotContain("CONTRACT_PLANNED");
    }

    @Test
    void productChangesAreMergedAcrossReleasesToo() throws Exception {
        var input = publishNew("p-in-" + shortId(), null, List.of(rule("A", "VARCHAR(10)")), "1.0.0");
        var output = publishNew("p-out-" + shortId(), null, List.of(rule("A", "VARCHAR(10)")), "1.0.0");
        var mainProduct = product(null, List.of(port("OUTPUT", output, "1.0.0")));
        var mainId = mainProduct.path("id").asText();
        publishProduct(mainId);
        var release = createRelease("r-" + shortId(), null);
        var forked = send(post("/api/releases/" + id(release) + "/products/" + mainId + "/fork"), null, anna(), 200);
        var forkId = forked.path("id").asText();

        var reopened = send(post("/api/products/" + mainId + "/reopen"), null, anna(), 200);
        var ports = portsOf(reopened);
        ports.add(port("INPUT", input, "1.0.0"));
        saveProduct(mainId, ports, "1.1.0", "Neuer Zweck");
        publishProduct(mainId);

        var merged = read("/api/products/" + forkId);
        assertThat(merged.path("ports").findValuesAsText("direction")).containsExactlyInAnyOrder("OUTPUT", "INPUT");
        assertThat(merged.path("purpose").asText()).isEqualTo("Neuer Zweck");
        assertThat(merged.path("publishedVersion").asText()).isEqualTo("1.1.0");
    }

    @Test
    void closeIsBlockedByUnpublishedContentAndByAnOpenBase() throws Exception {
        var main = publishNew("blockiert-" + shortId(), null, List.of(rule("A", "VARCHAR(10)")), "1.0.0");
        var release = createRelease("r-" + shortId(), null);
        var child = createRelease("r-" + shortId(), id(release));
        fork(release, main);

        send(post("/api/releases/" + id(release) + "/close"), null, rene(), 409);
        send(post("/api/releases/" + id(child) + "/close"), null, rene(), 409);
        send(post("/api/releases/" + id(release) + "/close"), null, anna(), 403);

        var view = findBy(read("/api/releases"), "id", id(release).toString());
        assertThat(view.path("closeBlockers").get(0).asText()).contains("noch nicht veröffentlicht");
    }

    @Test
    void closeMovesTheReleaseToTheMainlineTagsItAndRebasesDependents() throws Exception {
        var name = "abschluss-" + shortId();
        var main = publishNew(name, null, List.of(rule("A", "VARCHAR(10)")), "1.0.0");
        var release = createRelease("abschluss-r-" + shortId(), null);
        var child = createRelease("r-" + shortId(), id(release));
        var forkId = fork(release, main);
        var rules = rules(read("/api/mappings/" + forkId));
        rules.add(rule("NEU", "DATE"));
        publishExisting(forkId, rules, "1.1.0");

        var closed = send(post("/api/releases/" + id(release) + "/close"), null, rene(), 200);

        assertThat(closed.path("state").asText()).isEqualTo("RELEASED");
        assertThat(fileOnBranch("main", "contracts/" + name + "/odcs.yaml")).contains("version: 1.1.0", "NEU");
        assertThat(tags()).contains("release/" + release.path("name").asText());

        var mainView = findBy(read("/api/mappings"), "key", main.toString());
        assertThat(mainView.path("id").asText()).isEqualTo(forkId.toString());
        assertThat(mainView.path("contractVersion").asText()).isEqualTo("1.1.0");

        var archived = read("/api/mappings/" + main);
        assertThat(archived.path("archived").asBoolean()).isTrue();
        send(post("/api/mappings/" + main + "/reopen"), null, anna(), 409);

        var childView = findBy(read("/api/releases"), "id", id(child).toString());
        assertThat(childView.path("baseId").isNull()).isTrue();
        assertThat(findBy(read("/api/mappings?release=" + id(child)), "key", main.toString()).path("contractVersion").asText())
                .isEqualTo("1.1.0");

        var nextVersion = reopen(forkId);
        assertThat(nextVersion.path("releaseId").isNull()).isTrue();
    }

    @Test
    void closingOneReleaseMergesItIntoAParallelRelease() throws Exception {
        var main = publishNew("parallel-" + shortId(), null, List.of(rule("A", "VARCHAR(10)")), "1.0.0");
        var first = createRelease("r-" + shortId(), null);
        var second = createRelease("r-" + shortId(), null);
        var inFirst = fork(first, main);
        var inSecond = fork(second, main);
        var firstRules = rules(read("/api/mappings/" + inFirst));
        setField(firstRules, "A", "targetType", "VARCHAR(20)");
        publishExisting(inFirst, firstRules, "1.1.0");
        var secondRules = rules(read("/api/mappings/" + inSecond));
        setField(secondRules, "A", "description", "Aus Release zwei");
        save(inSecond, secondRules, "1.2.0");

        send(post("/api/releases/" + id(first) + "/close"), null, rene(), 200);

        var merged = read("/api/mappings/" + inSecond);
        assertThat(ruleBy(merged, "A").path("targetType").asText()).isEqualTo("VARCHAR(20)");
        assertThat(ruleBy(merged, "A").path("description").asText()).isEqualTo("Aus Release zwei");
        assertThat(merged.path("publishedVersion").asText()).isEqualTo("1.1.0");
        assertThat(merged.path("publishedCommit").asText()).isEqualTo(read("/api/mappings/" + inFirst).path("publishedCommit").asText());
    }

    @Test
    void releaseNamesThatCouldBreakGitRefsAreRejected() throws Exception {
        send(post("/api/releases"), Map.of("name", "../boese", "title", "x"), anna(), 400);
        send(post("/api/releases"), Map.of("name", "a..b", "title", "x"), anna(), 400);
    }

    @Test
    void graphAndTraceFollowTheSelectedRelease() throws Exception {
        var main = publishNew("sicht-graph-" + shortId(), null, List.of(rule("A", "VARCHAR(10)")), "1.0.0");
        var release = createRelease("r-" + shortId(), null);
        var inRelease = createMapping("nur-release-" + shortId(), id(release));

        assertThat(read("/api/graph").path("nodes").findValuesAsText("subjectId")).doesNotContain(inRelease.toString());
        var nodes = read("/api/graph?release=" + id(release)).path("nodes").findValuesAsText("subjectId");
        assertThat(nodes).contains(inRelease.toString(), main.toString());
    }

    private UUID publishNew(String name, UUID release, List<Map<String, Object>> rules, String version) throws Exception {
        var id = createMapping(name, release);
        publishExisting(id, rules, version);
        return id;
    }

    private void publishExisting(UUID id, List<Map<String, Object>> rules, String version) throws Exception {
        var saved = save(id, rules, version);
        assertThat(errors(saved)).isEmpty();
        var submitted = send(post("/api/mappings/" + id + "/submit"), null, anna(), 200);
        send(post("/api/mappings/" + id + "/approve"), Map.of("contentHash", submitted.path("contentHash").asText()), rene(), 200);
        send(post("/api/mappings/" + id + "/publish"), null, rene(), 200);
    }

    private JsonNode createRelease(String name, UUID base) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("name", name);
        body.put("title", "Release " + name);
        body.put("baseId", base);
        return send(post("/api/releases"), body, anna(), 201);
    }

    private UUID fork(JsonNode release, UUID key) throws Exception {
        var result = send(post("/api/releases/" + id(release) + "/mappings/" + key + "/fork"), null, anna(), 200);
        return UUID.fromString(result.path("id").asText());
    }

    private UUID createMapping(String name, UUID release) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("name", name);
        body.put("contractName", name);
        body.put("releaseId", release);
        var created = send(post("/api/mappings"), body, anna(), 201);
        return UUID.fromString(created.path("id").asText());
    }

    private JsonNode reopen(UUID id) throws Exception {
        return send(post("/api/mappings/" + id + "/reopen"), null, anna(), 200);
    }

    private JsonNode resolve(UUID id, String conflict, boolean takeTheirs) throws Exception {
        return send(post("/api/mappings/" + id + "/conflicts/" + conflict + "/resolve"), Map.of("takeTheirs", takeTheirs), anna(), 200);
    }

    private JsonNode save(UUID id, List<Map<String, Object>> rules, String version) throws Exception {
        return saveWithTable(id, rules, version, null);
    }

    private JsonNode saveWithTable(UUID id, List<Map<String, Object>> rules, String version, String table) throws Exception {
        var current = read("/api/mappings/" + id);
        var body = new HashMap<String, Object>();
        body.put("version", current.path("version").asLong());
        body.put("name", current.path("name").asText());
        body.put("contractVersion", version);
        body.put("domain", "sales");
        body.put("targetTable", table != null ? table : current.path("targetTable").isNull()
                ? "ZIEL_" + id.toString().substring(0, 8).toUpperCase() : current.path("targetTable").asText());
        body.put("ownerTeam", "data-team");
        body.put("rules", rules);
        return send(put("/api/mappings/" + id), body, anna(), 200);
    }

    private JsonNode product(UUID release, List<Map<String, Object>> ports) throws Exception {
        var name = "produkt-" + shortId();
        var body = new HashMap<String, Object>();
        body.put("name", name);
        body.put("productName", name);
        body.put("releaseId", release);
        var created = send(post("/api/products"), body, anna(), 201);
        return saveProduct(created.path("id").asText(), ports, "1.0.0", "Reporting");
    }

    private JsonNode saveProduct(String id, List<Map<String, Object>> ports, String version, String purpose) throws Exception {
        var current = read("/api/products/" + id);
        var body = new HashMap<String, Object>();
        body.put("version", current.path("version").asLong());
        body.put("name", current.path("name").asText());
        body.put("productVersion", version);
        body.put("domain", "sales");
        body.put("purpose", purpose);
        body.put("ownerTeam", "data-team");
        body.put("ports", ports);
        return send(put("/api/products/" + id), body, anna(), 200);
    }

    private void publishProduct(String id) throws Exception {
        var submitted = send(post("/api/products/" + id + "/submit"), null, anna(), 200);
        send(post("/api/products/" + id + "/approve"), Map.of("contentHash", submitted.path("contentHash").asText()), rene(), 200);
        send(post("/api/products/" + id + "/publish"), null, rene(), 200);
    }

    private static Map<String, Object> port(String direction, UUID contract, String version) {
        var port = new HashMap<String, Object>();
        port.put("direction", direction);
        port.put("name", direction.toLowerCase());
        port.put("contractKey", contract);
        port.put("contractVersion", version);
        return port;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> portsOf(JsonNode product) {
        var ports = new ArrayList<Map<String, Object>>();
        product.path("ports").forEach(port -> ports.add(JSON.convertValue(port, HashMap.class)));
        return ports;
    }

    private List<Map<String, Object>> rulesOf(UUID mapping) throws Exception {
        return rules(read("/api/mappings/" + mapping)).stream().peek(rule -> rule.remove("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rules(JsonNode mapping) {
        var rules = new ArrayList<Map<String, Object>>();
        mapping.path("rules").forEach(rule -> rules.add(JSON.convertValue(rule, HashMap.class)));
        return rules;
    }

    private static void setField(List<Map<String, Object>> rules, String column, String field, Object value) {
        rules.stream().filter(rule -> column.equals(rule.get("targetColumn"))).forEach(rule -> rule.put(field, value));
    }

    private static Map<String, Object> rule(String column, String type) {
        var rule = new HashMap<String, Object>();
        rule.put("transformation", "Testregel");
        rule.put("targetColumn", column);
        rule.put("targetType", type);
        rule.put("required", false);
        rule.put("primaryKey", false);
        rule.put("requirementKey", "DATA-1");
        return rule;
    }

    private static Map<String, Object> sourced(String dataset, String column, String target) {
        var rule = rule(target, "DATE");
        rule.remove("transformation");
        rule.put("sourceDataset", dataset);
        rule.put("sourceColumn", column);
        return rule;
    }

    private static JsonNode ruleBy(JsonNode mapping, String column) {
        return findBy(mapping.path("rules"), "targetColumn", column);
    }

    private static List<String> eventTypes(JsonNode mapping) {
        return mapping.path("events").findValuesAsText("type");
    }

    private static UUID id(JsonNode release) {
        return UUID.fromString(release.path("id").asText());
    }

    private static String branch(JsonNode release) {
        return release.path("branch").asText();
    }

    private String fileOnBranch(String branch, String path) throws Exception {
        var clone = temp.resolve("verify-" + shortId());
        try (var git = Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(clone.toFile())
                .setBranch("refs/heads/" + branch).call()) {
            var file = clone.resolve(path);
            return Files.exists(file) ? Files.readString(file) : null;
        }
    }

    private List<String> tags() throws Exception {
        try (var git = Git.open(remote.toFile())) {
            return git.tagList().call().stream().map(ref -> ref.getName().replace("refs/tags/", "")).toList();
        }
    }

    private JsonNode send(MockHttpServletRequestBuilder request, Object body, RequestPostProcessor actor, int expected)
            throws Exception {
        request.with(actor).with(csrf());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
        }
        var result = mvc.perform(request).andExpect(status().is(expected)).andReturn();
        var content = result.getResponse().getContentAsString();
        return content.isEmpty() ? JSON.nullNode() : JSON.readTree(content);
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
