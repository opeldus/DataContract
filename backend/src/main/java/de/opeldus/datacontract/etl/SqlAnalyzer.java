package de.opeldus.datacontract.etl;

import de.opeldus.datacontract.etl.EtlModel.Access;
import de.opeldus.datacontract.etl.EtlModel.AssetKind;
import de.opeldus.datacontract.etl.EtlModel.TableAccess;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the tables a statement touches. DataStage SQL contains job parameters such as #SCHEMA# and ORCHESTRATE.COL that
 * regular SQL parsers reject, so this walks tokens and only needs the clauses that name tables.
 */
public final class SqlAnalyzer {

    private static final Set<String> NO_TABLE = Set.of("SELECT", "LATERAL", "TABLE", "UNNEST", "VALUES", "ONLY", "WHERE",
            "ON", "AS", "SET", "GROUP", "ORDER", "HAVING", "UNION", "WITH");
    private static final Set<String> ENDS_FROM = Set.of("WHERE", "GROUP", "ORDER", "HAVING", "UNION", "EXCEPT", "INTERSECT",
            "MINUS", "FETCH", "LIMIT", "QUALIFY", "ON", "SET", "WHEN");
    private static final Set<String> IGNORED = Set.of("DUAL", "SYSIBM.SYSDUMMY1");

    private SqlAnalyzer() {}

    private static final class Frame {
        boolean select;
        boolean inFrom;
    }

    public static List<TableAccess> tables(String sql) {
        var found = new LinkedHashSet<TableAccess>();
        var statement = new ArrayList<String>();
        for (var token : tokens(sql)) {
            if (token.equals(";")) {
                analyze(statement, found);
                statement = new ArrayList<>();
            } else {
                statement.add(token);
            }
        }
        analyze(statement, found);
        return List.copyOf(found);
    }

    private static void analyze(List<String> tokens, Set<TableAccess> found) {
        if (tokens.isEmpty()) {
            return;
        }
        var cte = commonTableExpressions(tokens);
        var frames = new ArrayDeque<Frame>();
        frames.push(new Frame());
        var merge = upper(tokens.get(0)).equals("MERGE");
        var expectTable = false;
        var access = Access.READ;
        var operation = "SELECT";
        for (var i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            var word = upper(token);
            var previous = i > 0 ? upper(tokens.get(i - 1)) : "";
            if (token.equals("(")) {
                expectTable = false;
                frames.push(new Frame());
                continue;
            }
            if (token.equals(")")) {
                if (frames.size() > 1) {
                    frames.pop();
                }
                continue;
            }
            var frame = frames.peek();
            if (token.equals(",")) {
                expectTable = frame.inFrom;
                access = Access.READ;
                operation = "SELECT";
                continue;
            }
            if (expectTable) {
                expectTable = false;
                var next = i + 1 < tokens.size() ? tokens.get(i + 1) : "";
                // When reading, a parenthesis means a table function; INSERT INTO t (a) names its columns that way.
                var function = access == Access.READ && next.equals("(");
                if (!NO_TABLE.contains(word) && !function && !cte.contains(word) && !isIgnored(word)) {
                    found.add(new TableAccess(normalize(token), access, AssetKind.TABLE, operation));
                }
                continue;
            }
            switch (word) {
                case "SELECT" -> frame.select = true;
                case "FROM" -> {
                    if (previous.equals("DELETE")) {
                        expectTable = true;
                        access = Access.WRITE;
                        operation = "DELETE";
                    } else if (frame.select) {
                        frame.inFrom = true;
                        expectTable = true;
                        access = Access.READ;
                        operation = "SELECT";
                    }
                }
                case "JOIN" -> {
                    expectTable = true;
                    access = Access.READ;
                    operation = "SELECT";
                }
                case "INTO" -> {
                    if (previous.equals("INSERT") || previous.equals("MERGE")) {
                        expectTable = true;
                        access = Access.WRITE;
                        operation = previous;
                    }
                }
                case "UPDATE" -> {
                    if (i == 0 || previous.equals(")")) {
                        expectTable = true;
                        access = Access.WRITE;
                        operation = "UPDATE";
                    }
                }
                case "TRUNCATE" -> {
                    expectTable = true;
                    access = Access.WRITE;
                    operation = "TRUNCATE";
                    if (i + 1 < tokens.size() && upper(tokens.get(i + 1)).equals("TABLE")) {
                        i++;
                    }
                }
                case "USING" -> {
                    if (merge) {
                        expectTable = true;
                        access = Access.READ;
                        operation = "SELECT";
                    }
                }
                default -> {
                    if (ENDS_FROM.contains(word)) {
                        frame.inFrom = false;
                    }
                }
            }
        }
    }

    private static Set<String> commonTableExpressions(List<String> tokens) {
        var names = new HashSet<String>();
        for (var i = 1; i + 1 < tokens.size(); i++) {
            var previous = upper(tokens.get(i - 1));
            if (previous.equals("WITH") || previous.equals("RECURSIVE") || previous.equals(",")) {
                var after = i + 1;
                if (tokens.get(after).equals("(")) {
                    while (after < tokens.size() && !tokens.get(after).equals(")")) {
                        after++;
                    }
                    after++;
                }
                if (after + 1 < tokens.size() && upper(tokens.get(after)).equals("AS") && tokens.get(after + 1).equals("(")) {
                    names.add(upper(tokens.get(i)));
                }
            }
        }
        return names;
    }

    private static boolean isIgnored(String word) {
        return IGNORED.contains(word) || word.startsWith("ORCHESTRATE.");
    }

    private static String upper(String token) {
        return token.toUpperCase(Locale.ROOT);
    }

    private static String normalize(String token) {
        return upper(token.replace("\"", ""));
    }

    private static List<String> tokens(String sql) {
        var tokens = new ArrayList<String>();
        var length = sql.length();
        var i = 0;
        while (i < length) {
            var current = sql.charAt(i);
            if (Character.isWhitespace(current)) {
                i++;
            } else if (current == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                var end = sql.indexOf('\n', i);
                i = end < 0 ? length : end;
            } else if (current == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                var end = sql.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else if (current == '\'') {
                i++;
                while (i < length) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < length && sql.charAt(i + 1) == '\'') {
                            i++;
                        } else {
                            break;
                        }
                    }
                    i++;
                }
                i++;
                tokens.add("''");
            } else if (isNameCharacter(current) || current == '"') {
                var start = i;
                while (i < length && (isNameCharacter(sql.charAt(i)) || sql.charAt(i) == '"')) {
                    if (sql.charAt(i) == '"') {
                        var end = sql.indexOf('"', i + 1);
                        i = end < 0 ? length : end + 1;
                    } else {
                        i++;
                    }
                }
                tokens.add(sql.substring(start, i));
            } else {
                tokens.add(String.valueOf(current));
                i++;
            }
        }
        return tokens;
    }

    private static boolean isNameCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$' || value == '#' || value == '@' || value == '.';
    }
}
