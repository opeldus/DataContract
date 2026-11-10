package de.opeldus.datacontract.etl;

import de.opeldus.datacontract.etl.EtlGraph.EdgeKind;
import de.opeldus.datacontract.etl.EtlGraph.NodeKind;
import de.opeldus.datacontract.etl.EtlModel.Access;
import de.opeldus.datacontract.etl.EtlModel.Job;
import de.opeldus.datacontract.etl.EtlModel.Link;
import de.opeldus.datacontract.etl.EtlModel.Stage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Writes sequence diagrams as Mermaid text. Names come from uploaded files, so everything is escaped. */
final class MermaidWriter {

    private static final int MAX_LABEL = 80;

    private MermaidWriter() {}

    private static final class Diagram {
        private final Map<String, String> participants = new LinkedHashMap<>();
        private final List<String> messages = new ArrayList<>();

        String participant(String label) {
            return participants.computeIfAbsent(label, key -> "P" + (participants.size() + 1));
        }

        void message(String from, String arrow, String to, String text) {
            messages.add("    " + participant(from) + arrow + participant(to) + ": " + escape(text));
        }

        String render() {
            var out = new StringBuilder("sequenceDiagram\n");
            participants.forEach((label, id) -> out.append("    participant ").append(id).append(" as ").append(breakQualified(escape(label))).append('\n'));
            messages.forEach(message -> out.append(message).append('\n'));
            return out.toString();
        }
    }

    static String job(Job job) {
        var diagram = new Diagram();
        var names = new HashMap<String, String>();
        job.stages().forEach(stage -> names.put(stage.id(), stage.name()));
        var ordered = inFlowOrder(job.stages(), Stage::id, job.links().stream().map(link -> Map.entry(link.from(), link.to())).toList());
        for (var stage : ordered) {
            diagram.participant(stage.name());
        }
        for (var stage : ordered) {
            for (var table : stage.tables()) {
                if (table.access() == Access.READ) {
                    diagram.message(stage.name(), "->>", table.name(), table.operation());
                    diagram.message(table.name(), "-->>", stage.name(), "Datensätze");
                }
            }
            if (stage.calledJob() != null) {
                diagram.message(stage.name(), "->>", "Job " + stage.calledJob(), "startet");
                diagram.message("Job " + stage.calledJob(), "-->>", stage.name(), "Status");
            }
            for (var table : stage.tables()) {
                if (table.access() == Access.WRITE) {
                    diagram.message(stage.name(), "->>", table.name(), table.operation());
                }
            }
            // Jobs often write many links between the same two stages, e.g. one per log message; one arrow is enough.
            var byTarget = new LinkedHashMap<String, List<Link>>();
            for (var link : job.links()) {
                if (link.from().equals(stage.id())) {
                    byTarget.computeIfAbsent(link.to(), key -> new ArrayList<>()).add(link);
                }
            }
            byTarget.forEach((target, links) -> {
                var link = links.get(0);
                var text = link.name() == null ? "weiter" : link.name();
                if (link.condition() != null) {
                    text += " [" + link.condition() + "]";
                }
                if (links.size() > 1) {
                    text += " und " + (links.size() - 1) + " weitere";
                }
                diagram.message(stage.name(), "->>", names.get(target), text);
            });
        }
        return diagram.render();
    }

    static String chain(EtlGraph graph) {
        var diagram = new Diagram();
        var labels = new HashMap<String, String>();
        graph.nodes().forEach(node -> labels.put(node.id(), node.kind() == NodeKind.MISSING_JOB ? node.label() + " (nicht importiert)" : node.label()));
        var ordered = inFlowOrder(graph.nodes(), EtlGraph.Node::id, graph.edges().stream().map(edge -> Map.entry(edge.from(), edge.to())).toList());
        for (var node : ordered) {
            diagram.participant(labels.get(node.id()));
        }
        for (var node : ordered) {
            if (node.kind() == NodeKind.TABLE || node.kind() == NodeKind.FILE) {
                continue;
            }
            for (var edge : graph.edges()) {
                if (edge.to().equals(node.id()) && edge.kind() != EdgeKind.WRITES) {
                    diagram.message(labels.get(edge.from()), "->>", labels.get(node.id()), edge.kind() == EdgeKind.STARTS ? "startet" : "liest");
                }
            }
            for (var edge : graph.edges()) {
                if (edge.from().equals(node.id()) && edge.kind() == EdgeKind.WRITES) {
                    diagram.message(labels.get(node.id()), "->>", labels.get(edge.to()), "schreibt");
                }
            }
        }
        return diagram.render();
    }

    /** Sources first. Cycles can exist between jobs, their members simply follow in their original order. */
    private static <T> List<T> inFlowOrder(List<T> items, Function<T, String> id, List<Map.Entry<String, String>> edges) {
        var open = new ArrayList<>(items);
        var placed = new LinkedHashSet<String>();
        var result = new ArrayList<T>();
        while (!open.isEmpty()) {
            var next = open.stream()
                    .filter(item -> edges.stream().noneMatch(edge -> edge.getValue().equals(id.apply(item))
                            && !placed.contains(edge.getKey()) && !edge.getKey().equals(id.apply(item))))
                    .findFirst().orElse(open.get(0));
            open.remove(next);
            placed.add(id.apply(next));
            result.add(next);
        }
        return result;
    }

    /** Puts the table of SCHEMA.TABLE on its own line, so participant boxes stay narrow. Runs after escaping. */
    static String breakQualified(String label) {
        return label.replaceFirst("^([\\w.]+)\\.(\\w+)$", "$1.<br>$2");
    }

    /** Mermaid reads # as the start of an entity, ; as the end of a statement, and would render the rest as markup. */
    static String escape(String text) {
        var flat = text.replaceAll("\\s+", " ").strip();
        if (flat.length() > MAX_LABEL) {
            flat = flat.substring(0, MAX_LABEL - 1) + "…";
        }
        var out = new StringBuilder();
        for (var character : flat.toCharArray()) {
            switch (character) {
                case '#', ';', '<', '>', '%', ':', '"', '&', '{', '}' -> out.append('#').append((int) character).append(';');
                default -> out.append(character);
            }
        }
        return out.toString();
    }
}
