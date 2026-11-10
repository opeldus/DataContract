package de.opeldus.datacontract.etl;

import java.util.List;

/** What the analysis derives from a job. Stored as JSON, so it can change without touching the export. */
public final class EtlModel {

    private EtlModel() {}

    public enum JobKind { PARALLEL, SEQUENCE, UNKNOWN }

    public enum Access { READ, WRITE }

    public enum AssetKind { TABLE, FILE }

    public record TableAccess(String name, Access access, AssetKind kind, String operation) {}

    public record Stage(String id, String name, String oleType, String stageType, List<TableAccess> tables,
            List<String> sql, String calledJob) {}

    public record Link(String from, String to, String name, String condition) {}

    public record Job(String name, JobKind kind, String category, String description, List<Stage> stages,
            List<Link> links, List<String> warnings) {}
}
