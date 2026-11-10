package de.opeldus.datacontract.etl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the block structure of a DataStage DSX export. IBM publishes no specification, so this only relies on what all
 * known parsers agree on: nested BEGIN/END blocks, "Name value" properties and values spanning lines between markers.
 */
public final class DsxParser {

    private static final String MULTILINE_MARKER = "=+=+=+=";
    private static final Pattern PROPERTY = Pattern.compile("^([A-Za-z_][\\w-]*)\\s+(.*)$", Pattern.DOTALL);

    private DsxParser() {}

    public record Block(String type, Map<String, String> properties, List<Block> children) {

        public String property(String name) {
            return properties.get(name);
        }

        public List<Block> children(String childType) {
            return children.stream().filter(child -> child.type().equals(childType)).toList();
        }
    }

    public static class DsxFormatException extends RuntimeException {
        DsxFormatException(String message) {
            super(message);
        }
    }

    public static boolean looksLikeDsx(String text) {
        return text.stripLeading().startsWith("BEGIN HEADER");
    }

    /** Returns the top level blocks. */
    public static List<Block> parse(String text) {
        var root = new Block("ROOT", new LinkedHashMap<>(), new ArrayList<>());
        var open = new ArrayDeque<Block>();
        open.push(root);
        var lines = text.split("\\R", -1);
        for (var index = 0; index < lines.length; index++) {
            var line = lines[index].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("BEGIN ")) {
                var block = new Block(line.substring(6).strip(), new LinkedHashMap<>(), new ArrayList<>());
                open.peek().children().add(block);
                open.push(block);
            } else if (line.startsWith("END ")) {
                var type = line.substring(4).strip();
                if (open.size() == 1 || !open.peek().type().equals(type)) {
                    throw new DsxFormatException("Zeile " + (index + 1) + ": unerwartetes END " + type + ".");
                }
                open.pop();
            } else {
                var property = PROPERTY.matcher(line);
                if (!property.matches()) {
                    continue;
                }
                var value = property.group(2);
                if (value.startsWith(MULTILINE_MARKER)) {
                    var collected = new StringBuilder(value.substring(MULTILINE_MARKER.length()));
                    var closed = endMarker(collected);
                    while (closed < 0 && index + 1 < lines.length) {
                        collected.append('\n').append(lines[++index]);
                        closed = endMarker(collected);
                    }
                    if (closed < 0) {
                        throw new DsxFormatException("Ein mehrzeiliger Wert in " + open.peek().type() + " endet nicht.");
                    }
                    open.peek().properties().put(property.group(1), collected.substring(0, closed).strip());
                } else {
                    open.peek().properties().put(property.group(1), unquote(value));
                }
            }
        }
        if (open.size() > 1) {
            throw new DsxFormatException("Der Block " + open.peek().type() + " wird nicht geschlossen.");
        }
        return root.children();
    }

    private static int endMarker(StringBuilder value) {
        return value.indexOf(MULTILINE_MARKER);
    }

    private static String unquote(String value) {
        if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) {
            return value;
        }
        var result = new StringBuilder(value.length());
        for (var i = 1; i < value.length() - 1; i++) {
            var current = value.charAt(i);
            if (current == '\\' && i + 2 < value.length()) {
                var next = value.charAt(i + 1);
                if (next == '"' || next == '\\') {
                    result.append(next);
                    i++;
                    continue;
                }
                if (next == 'n') {
                    result.append('\n');
                    i++;
                    continue;
                }
            }
            result.append(current);
        }
        return result.toString();
    }
}
