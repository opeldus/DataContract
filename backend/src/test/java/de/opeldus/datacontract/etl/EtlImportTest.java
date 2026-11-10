package de.opeldus.datacontract.etl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.opeldus.datacontract.etl.EtlGraph.EdgeKind;
import de.opeldus.datacontract.etl.EtlModel.Access;
import de.opeldus.datacontract.etl.EtlModel.JobKind;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EtlImportTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:etl;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    MockMvc mvc;

    private static byte[] sample() {
        return SampleDsx.bytes();
    }

    private static byte[] zip(String path, byte[] content) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(path));
            zip.write(content);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void readsStagesLinksAndTablesFromDsx() throws Exception {
        var result = EtlImporter.read(sample(), "beispiel.dsx");
        assertThat(result.jobs()).extracting(EtlModel.Job::name).containsExactly("SEQ_KUNDEN_TAG", "JOB_KUNDE_LADEN", "JOB_UMSATZ_VERDICHTEN");

        var load = result.jobs().get(1);
        assertThat(load.kind()).isEqualTo(JobKind.PARALLEL);
        assertThat(load.stages()).extracting(EtlModel.Stage::name).containsExactly("DB2_Kunde", "TRF_bereinigen", "SF_Staging");
        assertThat(load.links()).extracting(EtlModel.Link::from, EtlModel.Link::to, EtlModel.Link::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("V0S1", "V0S2", "lnk_quelle"), org.assertj.core.groups.Tuple.tuple("V0S2", "V0S3", "lnk_ziel"));
        assertThat(load.stages().get(0).tables()).extracting(EtlModel.TableAccess::name).containsExactly("SALES.KUNDE", "SALES.AUFTRAG");
        assertThat(load.stages().get(2).tables().get(0).access()).isEqualTo(Access.WRITE);
        assertThat(load.warnings()).isEmpty();

        var sequence = result.jobs().get(0);
        assertThat(sequence.kind()).isEqualTo(JobKind.SEQUENCE);
        assertThat(sequence.stages()).extracting(EtlModel.Stage::calledJob).containsExactly("JOB_KUNDE_LADEN", "JOB_UMSATZ_VERDICHTEN");
        assertThat(sequence.links()).singleElement().satisfies(link -> assertThat(link.condition()).isEqualTo("OK"));
    }

    @Test
    void neverKeepsConnectionDetails() throws Exception {
        var result = EtlImporter.read(sample(), "beispiel.dsx");
        assertThat(JSON.writeValueAsString(result.jobs())).doesNotContain("streng-geheim").doesNotContain("DB2_INSTANZ");
    }

    @Test
    void warnsAboutWhatItCannotResolve() throws Exception {
        var job = EtlImporter.read(sample(), "beispiel.dsx").jobs().get(2);
        assertThat(job.warnings()).anyMatch(warning -> warning.contains("Container"));
    }

    @Test
    void connectsJobsThroughSharedTablesAndSequences() throws Exception {
        var jobs = new LinkedHashMap<UUID, EtlModel.Job>();
        EtlImporter.read(sample(), "beispiel.dsx").jobs().forEach(job -> jobs.put(UUID.randomUUID(), job));
        var chain = EtlGraph.of(jobs).chainOf("table:DWH.MART.KUNDENUMSATZ");
        assertThat(chain.edges()).extracting(EtlGraph.Edge::kind).contains(EdgeKind.READS, EdgeKind.WRITES, EdgeKind.STARTS);
        assertThat(chain.nodes()).extracting(EtlGraph.Node::id)
                .contains("table:SALES.KUNDE", "job:JOB_KUNDE_LADEN", "table:DWH.STG.KUNDE", "job:JOB_UMSATZ_VERDICHTEN")
                .doesNotContain("table:GANZ.ANDERS");
        var mermaid = MermaidWriter.chain(chain);
        assertThat(mermaid).startsWith("sequenceDiagram\n").contains("JOB_KUNDE_LADEN", "schreibt", "liest");
        assertThat(mermaid.indexOf("as SALES.<br>KUNDE")).isLessThan(mermaid.indexOf("as DWH.MART.<br>KUNDENUMSATZ"));
    }

    @Test
    void drawsParallelLinksAsOneMessage() {
        var stages = List.of(new EtlModel.Stage("A", "TR_A", null, null, List.of(), List.of(), null),
                new EtlModel.Stage("B", "FU_B", null, null, List.of(), List.of(), null));
        var links = List.of(new EtlModel.Link("A", "B", "writeLog_1", null), new EtlModel.Link("A", "B", "writeLog_2", null),
                new EtlModel.Link("A", "B", "writeLog_3", null));
        var mermaid = MermaidWriter.job(new EtlModel.Job("J", JobKind.PARALLEL, null, null, stages, links, List.of()));
        assertThat(mermaid).containsOnlyOnce("->>").contains("writeLog_1 und 2 weitere");
    }

    @Test
    void escapesNamesInDiagrams() {
        assertThat(MermaidWriter.escape("#SCHEMA#.T;\n<script>")).isEqualTo("#35;SCHEMA#35;.T#59; #60;script#62;");
    }

    @Test
    void readsDsxInsideAPackageAndNamesWhatItCannotRead() throws Exception {
        assertThat(EtlImporter.read(zip("x/a.dsx", sample()), "p.isx").jobs()).hasSize(3);

        var isx = zip("proj/Jobs/JOB.pjb", "<?xml version='1.0'?><!-- c --><ds:Unknown secret='x'><a/></ds:Unknown>".getBytes(StandardCharsets.UTF_8));
        var result = EtlImporter.read(isx, "p.isx");
        assertThat(result.jobs()).isEmpty();
        assertThat(result.unreadable()).singleElement().satisfies(entry -> {
            assertThat(entry.path()).isEqualTo("proj/Jobs/JOB.pjb");
            assertThat(entry.content()).isEqualTo("XML mit Wurzelelement ds:Unknown");
        });
    }

    @Test
    void rejectsPackagesThatUnpackTooFar() throws Exception {
        var bomb = zip("gross.bin", new byte[(int) EtlImporter.MAX_ENTRY_BYTES + 1]);
        assertThatThrownBy(() -> EtlImporter.read(bomb, "p.isx")).isInstanceOf(EtlImporter.TooLargeException.class);
    }

    @Test
    void reportsBrokenFilesWithoutImportingThem() {
        var broken = "BEGIN HEADER\nEND HEADER\nBEGIN DSJOB\n Identifier \"X\"\n".getBytes(StandardCharsets.UTF_8);
        var result = EtlImporter.read(broken, "kaputt.dsx");
        assertThat(result.jobs()).isEmpty();
        assertThat(result.warnings()).singleElement().satisfies(warning -> assertThat(warning).contains("kaputt.dsx", "DSJOB"));
    }

    @Test
    void importsReplacesAndDeletesThroughTheApi() throws Exception {
        var file = new MockMultipartFile("file", "beispiel.dsx", "text/plain", sample());
        var reviewer = user("rene").roles("REVIEWER");
        var editor = user("anna").roles("EDITOR");
        mvc.perform(multipart("/api/etl/imports").file(file).with(reviewer).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/etl/imports").file(file).with(editor).with(csrf())).andExpect(status().isOk());
        var report = JSON.readTree(mvc.perform(multipart("/api/etl/imports").file(file).with(editor).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(report.get("imported")).hasSize(3);

        var jobs = JSON.readTree(mvc.perform(get("/api/etl/jobs").with(reviewer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(jobs).hasSize(3);
        var id = jobs.get(0).get("id").asText();
        var detail = JSON.readTree(mvc.perform(get("/api/etl/jobs/" + id).with(reviewer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.get("sequence").asText()).startsWith("sequenceDiagram");
        mvc.perform(get("/api/etl/sequence").param("node", "table:DWH.STG.KUNDE").with(reviewer)).andExpect(status().isOk());
        mvc.perform(get("/api/etl/sequence").param("node", "table:GIBT.ES.NICHT").with(reviewer)).andExpect(status().isNotFound());

        mvc.perform(delete("/api/etl/jobs/" + id).with(editor).with(csrf())).andExpect(status().isNoContent());
        assertThat(JSON.readTree(mvc.perform(get("/api/etl/jobs").with(reviewer)).andReturn().getResponse().getContentAsString())).hasSize(2);
    }
}
