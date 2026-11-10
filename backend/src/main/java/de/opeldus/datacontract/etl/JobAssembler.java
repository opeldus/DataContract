package de.opeldus.datacontract.etl;

import de.opeldus.datacontract.etl.DsxParser.Block;
import de.opeldus.datacontract.etl.EtlModel.Access;
import de.opeldus.datacontract.etl.EtlModel.AssetKind;
import de.opeldus.datacontract.etl.EtlModel.Job;
import de.opeldus.datacontract.etl.EtlModel.JobKind;
import de.opeldus.datacontract.etl.EtlModel.Link;
import de.opeldus.datacontract.etl.EtlModel.Stage;
import de.opeldus.datacontract.etl.EtlModel.TableAccess;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Turns the generic blocks of one DSJOB into stages and links. What it cannot interpret becomes a warning, never a guess. */
final class JobAssembler {

    private static final Pattern CDATA_ELEMENT = Pattern.compile("<([A-Za-z_][\\w.-]*)(?:\\s[^<>]*)?>\\s*<!\\[CDATA\\[(.*?)]]>", Pattern.DOTALL);
    private static final Pattern SQL_START = Pattern.compile("^\\s*(?:--[^\\n]*\\n\\s*)*(WITH|SELECT|INSERT|UPDATE|DELETE|MERGE|TRUNCATE)\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NOT_SQL = Pattern.compile("(?i).*(description|desc|annotation|comment).*");
    private static final Pattern TABLE_KEY = Pattern.compile("(?i)^(TableName|Table|TargetTable|SourceTable)$");
    private static final Pattern FILE_KEY = Pattern.compile("(?i)^(file|filename|dataset)$");
    private static final Pattern JOB_KEY = Pattern.compile("(?i)^(jobname|job)$");
    private static final Pattern DATABASE_STAGE = Pattern.compile("(?i).*(connector|db2|oracle|snowflake|odbc|teradata|netezza).*");

    private JobAssembler() {}

    private record Entry(String key, String value) {}

    static Job assemble(Block dsJob) {
        var name = dsJob.property("Identifier");
        if (name == null) {
            throw new DsxParser.DsxFormatException("Ein DSJOB-Block hat keinen Namen.");
        }
        var warnings = new ArrayList<String>();
        var records = dsJob.children("DSRECORD");
        var byId = new HashMap<String, Block>();
        for (var record : records) {
            if (record.property("Identifier") != null) {
                byId.put(record.property("Identifier"), record);
            }
        }
        var stageBlocks = records.stream().filter(JobAssembler::isStage).toList();
        var stageNames = new HashMap<String, String>();
        stageBlocks.forEach(stage -> stageNames.put(stage.property("Identifier"), stage.property("Name")));

        var stages = new ArrayList<Stage>();
        for (var block : stageBlocks) {
            stages.add(stage(block, warnings));
        }
        var links = new LinkedHashSet<Link>();
        for (var block : stageBlocks) {
            collectLinks(block, byId, stageNames, links, warnings);
        }
        if (stages.isEmpty()) {
            warnings.add("Im Job wurden keine Stages erkannt.");
        }
        var definition = records.stream().filter(record -> "CJobDefn".equals(record.property("OLEType"))).findFirst();
        var description = definition.map(block -> firstNonBlank(block.property("Description"), block.property("ShortDesc")));
        return new Job(name, kindOf(stageBlocks), definition.map(block -> block.property("Category")).orElse(null),
                description.orElse(null), stages, List.copyOf(links), warnings);
    }

    private static boolean isStage(Block record) {
        var type = record.property("OLEType");
        return record.property("StageType") != null || (type != null && (type.startsWith("CJS") && !type.contains("Output")
                && !type.contains("Input") || type.endsWith("Stage")));
    }

    /** Decided from the content, because the numeric JobType values of the export are not documented. */
    private static JobKind kindOf(List<Block> stages) {
        if (stages.stream().anyMatch(stage -> stage.property("OLEType") != null && stage.property("OLEType").startsWith("CJS"))) {
            return JobKind.SEQUENCE;
        }
        return stages.isEmpty() ? JobKind.UNKNOWN : JobKind.PARALLEL;
    }

    private static Stage stage(Block block, List<String> warnings) {
        var id = block.property("Identifier");
        var name = firstNonBlank(block.property("Name"), id);
        var oleType = block.property("OLEType");
        var stageType = block.property("StageType");
        var entries = entries(block);
        var hasInputs = hasPins(block.property("InputPins"));
        var hasOutputs = hasPins(block.property("OutputPins"));

        var sql = new LinkedHashSet<String>();
        var tables = new LinkedHashSet<TableAccess>();
        String calledJob = null;
        for (var entry : entries) {
            if (entry.value().isBlank()) {
                continue;
            }
            if (!NOT_SQL.matcher(entry.key()).matches() && SQL_START.matcher(entry.value()).matches()) {
                sql.add(entry.value().strip());
                tables.addAll(SqlAnalyzer.tables(entry.value()));
            } else if (JOB_KEY.matcher(entry.key()).matches() && calledJob == null) {
                calledJob = entry.value().strip();
            }
        }
        for (var entry : entries) {
            var kind = TABLE_KEY.matcher(entry.key()).matches() ? AssetKind.TABLE
                    : FILE_KEY.matcher(entry.key()).matches() ? AssetKind.FILE : null;
            if (kind == null || entry.value().isBlank()) {
                continue;
            }
            var asset = kind == AssetKind.TABLE ? entry.value().strip().replace("\"", "").toUpperCase(java.util.Locale.ROOT) : entry.value().strip();
            if (tables.stream().anyMatch(known -> known.name().equals(asset))) {
                continue;
            }
            if (hasInputs == hasOutputs) {
                warnings.add("Stage " + name + ": Für " + asset + " ist nicht erkennbar, ob gelesen oder geschrieben wird.");
            } else {
                tables.add(new TableAccess(asset, hasOutputs ? Access.READ : Access.WRITE, kind, hasOutputs ? "liest" : "schreibt"));
            }
        }
        if (tables.isEmpty() && stageType != null && DATABASE_STAGE.matcher(stageType).matches()) {
            warnings.add("Stage " + name + " (" + stageType + "): keine Tabelle erkannt.");
        }
        if (oleType != null && oleType.contains("Container")) {
            warnings.add("Stage " + name + " ist ein Container. Sein Inhalt wird nicht aufgelöst.");
        }
        if ("CJSJobActivity".equals(oleType) && calledJob == null) {
            warnings.add("Stage " + name + ": Der gestartete Job wurde nicht erkannt.");
        }
        if (calledJob != null && calledJob.contains("#")) {
            warnings.add("Stage " + name + ": Der Jobname hängt vom Parameter " + calledJob + " ab und bleibt offen.");
        }
        return new Stage(id, name, oleType, stageType, List.copyOf(tables), List.copyOf(sql), calledJob);
    }

    private static List<Entry> entries(Block block) {
        var entries = new ArrayList<Entry>();
        block.properties().forEach((key, value) -> addEntry(key, value, entries));
        for (var sub : block.children("DSSUBRECORD")) {
            if (sub.property("Name") != null && sub.property("Value") != null) {
                addEntry(sub.property("Name"), sub.property("Value"), entries);
            }
        }
        return entries;
    }

    /** Connector stages keep their settings as XML inside one property. */
    private static void addEntry(String key, String value, List<Entry> entries) {
        if (value.contains("<![CDATA[")) {
            var matcher = CDATA_ELEMENT.matcher(value);
            while (matcher.find()) {
                entries.add(new Entry(matcher.group(1), matcher.group(2)));
            }
        } else {
            entries.add(new Entry(key, value));
        }
    }

    private static void collectLinks(Block stage, Map<String, Block> byId, Map<String, String> stageNames,
            LinkedHashSet<Link> links, List<String> warnings) {
        var id = stage.property("Identifier");
        for (var pin : pins(stage.property("OutputPins"))) {
            var other = partnerStage(pin, stage, byId, stageNames, warnings);
            if (other != null) {
                links.add(link(id, other, byId.get(pin)));
            }
        }
        for (var pin : pins(stage.property("InputPins"))) {
            var other = partnerStage(pin, stage, byId, stageNames, warnings);
            // The other side's output pin names the same link; only a stage that has no such pin adds something here.
            if (other != null && !hasOutputPinTo(byId.get(other), id, byId)) {
                links.add(link(other, id, byId.get(pin)));
            }
        }
    }

    private static boolean hasOutputPinTo(Block source, String target, Map<String, Block> byId) {
        return source != null && pins(source.property("OutputPins")).stream().map(byId::get)
                .anyMatch(pin -> pin != null && pin.property("Partner") != null && pin.property("Partner").split("\\|")[0].equals(target));
    }

    private static String partnerStage(String pin, Block stage, Map<String, Block> byId, Map<String, String> stageNames, List<String> warnings) {
        var record = byId.get(pin);
        var partner = record == null ? null : record.property("Partner");
        if (partner == null || partner.isBlank()) {
            warnings.add("Stage " + stage.property("Name") + ": Die Verbindung " + pin + " führt nirgendwohin.");
            return null;
        }
        var other = partner.split("\\|")[0];
        if (!stageNames.containsKey(other)) {
            warnings.add("Stage " + stage.property("Name") + ": Die Verbindung " + pin + " zeigt auf die unbekannte Stage " + other + ".");
            return null;
        }
        return other;
    }

    private static Link link(String from, String to, Block pin) {
        var condition = firstNonBlank(pin.property("Expression"), pin.property("ConditionType"));
        return new Link(from, to, pin.property("Name"), condition);
    }

    private static boolean hasPins(String pins) {
        return pins != null && !pins.isBlank();
    }

    private static List<String> pins(String pins) {
        return hasPins(pins) ? List.of(pins.split("\\|")) : List.of();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
