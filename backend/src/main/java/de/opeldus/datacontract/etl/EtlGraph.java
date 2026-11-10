package de.opeldus.datacontract.etl;

import de.opeldus.datacontract.etl.EtlModel.Access;
import de.opeldus.datacontract.etl.EtlModel.AssetKind;
import de.opeldus.datacontract.etl.EtlModel.Job;
import de.opeldus.datacontract.etl.EtlModel.JobKind;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The processing chain across jobs: a job continues another one where it reads what the other one writes. */
public record EtlGraph(List<Node> nodes, List<Edge> edges) {

    public enum NodeKind { JOB, SEQUENCE, MISSING_JOB, TABLE, FILE }

    public enum EdgeKind { READS, WRITES, STARTS }

    public record Node(String id, NodeKind kind, String label, UUID jobId) {}

    public record Edge(String from, String to, EdgeKind kind) {}

    public static EtlGraph of(Map<UUID, Job> jobs) {
        var nodes = new LinkedHashMap<String, Node>();
        var edges = new LinkedHashSet<Edge>();
        jobs.forEach((id, job) -> {
            var kind = job.kind() == JobKind.SEQUENCE ? NodeKind.SEQUENCE : NodeKind.JOB;
            nodes.put(jobNode(job.name()), new Node(jobNode(job.name()), kind, job.name(), id));
        });
        for (var job : jobs.values()) {
            var self = jobNode(job.name());
            for (var stage : job.stages()) {
                for (var table : stage.tables()) {
                    var kind = table.kind() == AssetKind.FILE ? NodeKind.FILE : NodeKind.TABLE;
                    var asset = (kind == NodeKind.FILE ? "file:" : "table:") + table.name();
                    nodes.putIfAbsent(asset, new Node(asset, kind, table.name(), null));
                    edges.add(table.access() == Access.READ ? new Edge(asset, self, EdgeKind.READS) : new Edge(self, asset, EdgeKind.WRITES));
                }
                if (stage.calledJob() != null) {
                    var called = jobNode(stage.calledJob());
                    nodes.putIfAbsent(called, new Node(called, NodeKind.MISSING_JOB, stage.calledJob(), null));
                    edges.add(new Edge(self, called, EdgeKind.STARTS));
                }
            }
        }
        return new EtlGraph(List.copyOf(nodes.values()), List.copyOf(edges));
    }

    static String jobNode(String name) {
        return "job:" + name;
    }

    /** Everything that feeds into the node and everything it feeds into, which is its chain from source to target. */
    public EtlGraph chainOf(String nodeId) {
        var keep = new HashSet<String>(reachable(nodeId, true));
        keep.addAll(reachable(nodeId, false));
        keep.add(nodeId);
        return new EtlGraph(nodes.stream().filter(node -> keep.contains(node.id())).toList(),
                edges.stream().filter(edge -> keep.contains(edge.from()) && keep.contains(edge.to())).toList());
    }

    private Set<String> reachable(String start, boolean forward) {
        var seen = new HashSet<String>();
        var open = new ArrayDeque<>(List.of(start));
        while (!open.isEmpty()) {
            var current = open.pop();
            for (var edge : edges) {
                var from = forward ? edge.from() : edge.to();
                var to = forward ? edge.to() : edge.from();
                if (from.equals(current) && seen.add(to)) {
                    open.push(to);
                }
            }
        }
        return seen;
    }
}
