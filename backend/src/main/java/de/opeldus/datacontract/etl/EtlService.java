package de.opeldus.datacontract.etl;

import static de.opeldus.datacontract.common.ApiExceptions.badRequest;
import static de.opeldus.datacontract.common.ApiExceptions.notFound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.opeldus.datacontract.etl.EtlModel.Job;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EtlService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EtlJobRepository jobs;

    public EtlService(EtlJobRepository jobs) {
        this.jobs = jobs;
    }

    public record JobSummary(UUID id, String name, String kind, String category, int stages, int warnings,
            String sourceFile, String importedBy, Instant importedAt) {}

    public record JobDetail(UUID id, Job job, String sourceFile, String importedBy, Instant importedAt, String sequence) {}

    public record ImportReport(List<String> imported, List<EtlImporter.UnreadableEntry> unreadable, List<String> warnings) {}

    @Transactional
    public ImportReport importFile(byte[] data, String fileName, String actor) {
        EtlImporter.Result result;
        try {
            result = EtlImporter.read(data, fileName);
        } catch (DsxParser.DsxFormatException | EtlImporter.TooLargeException exception) {
            throw badRequest(exception.getMessage());
        }
        for (var job : result.jobs()) {
            var record = jobs.findByName(job.name()).orElseGet(() -> new EtlJobRecord(job.name()));
            record.replaceWith(job.kind().name(), job.category(), fileName, actor, write(job));
            jobs.save(record);
        }
        return new ImportReport(result.jobs().stream().map(Job::name).toList(), result.unreadable(), result.warnings());
    }

    @Transactional(readOnly = true)
    public List<JobSummary> list() {
        return jobs.findAllByOrderByNameAsc().stream().map(record -> {
            var job = read(record);
            return new JobSummary(record.getId(), record.getName(), record.getKind(), record.getCategory(),
                    job.stages().size(), job.warnings().size(), record.getSourceFile(), record.getImportedBy(), record.getImportedAt());
        }).toList();
    }

    @Transactional(readOnly = true)
    public JobDetail get(UUID id) {
        var record = jobs.findById(id).orElseThrow(() -> notFound("Den Job gibt es nicht."));
        var job = read(record);
        return new JobDetail(id, job, record.getSourceFile(), record.getImportedBy(), record.getImportedAt(), MermaidWriter.job(job));
    }

    @Transactional
    public void delete(UUID id) {
        jobs.deleteById(id);
    }

    @Transactional(readOnly = true)
    public EtlGraph graph() {
        var all = new LinkedHashMap<UUID, Job>();
        jobs.findAllByOrderByNameAsc().forEach(record -> all.put(record.getId(), read(record)));
        return EtlGraph.of(all);
    }

    @Transactional(readOnly = true)
    public String chainSequence(String nodeId) {
        var graph = graph();
        if (graph.nodes().stream().noneMatch(node -> node.id().equals(nodeId))) {
            throw notFound("Das Element gibt es nicht.");
        }
        return MermaidWriter.chain(graph.chainOf(nodeId));
    }

    private static String write(Job job) {
        try {
            return JSON.writeValueAsString(job);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Job read(EtlJobRecord record) {
        try {
            return JSON.readValue(record.getModel(), Job.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
