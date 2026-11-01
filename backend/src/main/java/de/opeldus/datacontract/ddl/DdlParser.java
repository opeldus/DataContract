package de.opeldus.datacontract.ddl;

import de.opeldus.datacontract.ddl.DdlSchema.Column;
import de.opeldus.datacontract.ddl.DdlSchema.DdlFile;
import de.opeldus.datacontract.ddl.DdlSchema.Table;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Reads only what the comparison needs from DB2 and Snowflake DDL: tables, columns, types, NOT NULL and primary keys.
 * Vendor clauses such as ORGANIZE BY or CLUSTER BY are skipped, so a full SQL grammar is not required. Statements are
 * applied in path order, which is how Flyway-style migrations build up a table.
 */
@Component
public class DdlParser {

    private static final Set<String> TABLE_MODIFIERS = Set.of("OR", "REPLACE", "GLOBAL", "LOCAL", "TEMPORARY", "TEMP",
            "TRANSIENT", "VOLATILE", "EXTERNAL");
    private static final Set<String> CONSTRAINT_KEYWORDS = Set.of("CONSTRAINT", "PRIMARY", "UNIQUE", "FOREIGN", "CHECK",
            "LIKE", "PERIOD", "INDEX", "KEY");
    private static final Set<String> ALTER_ACTIONS = Set.of("ADD", "DROP", "ALTER", "MODIFY", "RENAME");

    private enum Kind { WORD, QUOTED, NUMBER, TEXT, SYMBOL }

    private record Token(Kind kind, String value) {

        boolean isWord(String word) {
            return kind == Kind.WORD && value.equalsIgnoreCase(word);
        }

        boolean isSymbol(String symbol) {
            return kind == Kind.SYMBOL && value.equals(symbol);
        }

        boolean isIdentifier() {
            return kind == Kind.WORD || kind == Kind.QUOTED;
        }
    }

    public DdlSchema parse(Collection<DdlFile> files) {
        var state = new State();
        files.stream().sorted(Comparator.comparing(DdlFile::path, DdlParser::compareNaturally)).forEach(file -> {
            for (var statement : statements(tokenize(file.content()))) {
                try {
                    state.apply(statement, file.path());
                } catch (RuntimeException exception) {
                    state.warnings.add(file.path() + ": Eine Anweisung konnte nicht gelesen werden ("
                            + preview(statement) + ").");
                }
            }
        });
        var tables = new TreeMap<String, Table>();
        state.tables.forEach((key, table) -> tables.put(key, new Table(key, List.copyOf(table.columns.values()))));
        return new DdlSchema(tables, List.copyOf(state.warnings));
    }

    /** Orders V2 before V10, unlike a plain string comparison. */
    static int compareNaturally(String left, String right) {
        int a = 0;
        int b = 0;
        while (a < left.length() && b < right.length()) {
            char x = left.charAt(a);
            char y = right.charAt(b);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int endA = a;
                while (endA < left.length() && Character.isDigit(left.charAt(endA))) {
                    endA++;
                }
                int endB = b;
                while (endB < right.length() && Character.isDigit(right.charAt(endB))) {
                    endB++;
                }
                int result = new java.math.BigInteger(left.substring(a, endA))
                        .compareTo(new java.math.BigInteger(right.substring(b, endB)));
                if (result != 0) {
                    return result;
                }
                a = endA;
                b = endB;
            } else {
                if (x != y) {
                    return Character.compare(x, y);
                }
                a++;
                b++;
            }
        }
        return Integer.compare(left.length() - a, right.length() - b);
    }

    private static List<Token> tokenize(String sql) {
        var tokens = new ArrayList<Token>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? sql.length() : end + 1;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 2;
            } else if (c == '\'' || c == '"') {
                var text = new StringBuilder();
                i++;
                while (i < sql.length()) {
                    if (sql.charAt(i) == c) {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == c) {
                            text.append(c);
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    text.append(sql.charAt(i++));
                }
                i++;
                tokens.add(new Token(c == '"' ? Kind.QUOTED : Kind.TEXT, text.toString()));
            } else if (c == '$' && sql.startsWith("$$", i)) {
                int end = sql.indexOf("$$", i + 2);
                i = end < 0 ? sql.length() : end + 2;
                tokens.add(new Token(Kind.TEXT, ""));
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || "_$#@".indexOf(sql.charAt(i)) >= 0)) {
                    i++;
                }
                var word = sql.substring(start, i);
                // "@" also ends statements in DB2 scripts, so it only belongs to a word in the middle of one.
                if (word.endsWith("@")) {
                    word = word.substring(0, word.length() - 1);
                    i--;
                }
                tokens.add(new Token(Kind.WORD, word));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < sql.length() && (Character.isDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(Kind.NUMBER, sql.substring(start, i)));
            } else {
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c)));
                i++;
            }
        }
        return tokens;
    }

    private static List<List<Token>> statements(List<Token> tokens) {
        var statements = new ArrayList<List<Token>>();
        var current = new ArrayList<Token>();
        int depth = 0;
        for (var token : tokens) {
            if (token.isSymbol("(")) {
                depth++;
            } else if (token.isSymbol(")")) {
                depth--;
            }
            if (depth <= 0 && (token.isSymbol(";") || token.isSymbol("@"))) {
                if (!current.isEmpty()) {
                    statements.add(current);
                }
                current = new ArrayList<>();
                depth = 0;
            } else {
                current.add(token);
            }
        }
        if (!current.isEmpty()) {
            statements.add(current);
        }
        return statements;
    }

    private static String preview(List<Token> statement) {
        var text = new StringBuilder();
        for (var token : statement.subList(0, Math.min(6, statement.size()))) {
            text.append(text.isEmpty() ? "" : " ").append(token.value());
        }
        return text.toString();
    }

    private static final class MutableTable {
        final Map<String, Column> columns = new LinkedHashMap<>();
    }

    private static final class State {
        final Map<String, MutableTable> tables = new LinkedHashMap<>();
        final List<String> warnings = new ArrayList<>();

        void apply(List<Token> statement, String path) {
            var cursor = new Cursor(statement);
            if (cursor.acceptWord("CREATE")) {
                while (cursor.hasNext() && TABLE_MODIFIERS.contains(cursor.peek().value().toUpperCase(Locale.ROOT))
                        && cursor.peek().kind() == Kind.WORD) {
                    cursor.next();
                }
                if (cursor.acceptWord("TABLE")) {
                    createTable(cursor, statement, path);
                }
            } else if (cursor.acceptWord("ALTER")) {
                if (cursor.acceptWord("TABLE")) {
                    alterTable(cursor, statement, path);
                }
            } else if (cursor.acceptWord("DROP")) {
                if (cursor.acceptWord("TABLE")) {
                    acceptIfExists(cursor);
                    tables.remove(cursor.qualifiedName());
                }
            } else if (cursor.acceptWord("RENAME")) {
                cursor.acceptWord("TABLE");
                var from = cursor.qualifiedName();
                cursor.expectWord("TO");
                renameTable(from, cursor.qualifiedName(), path);
            }
        }

        private void createTable(Cursor cursor, List<Token> statement, String path) {
            if (cursor.acceptWord("IF")) {
                cursor.expectWord("NOT");
                cursor.expectWord("EXISTS");
            }
            var name = cursor.qualifiedName();
            if (!cursor.acceptSymbol("(")) {
                warnings.add(path + ": Tabelle " + name + " hat keine Spaltenliste (" + preview(statement) + ").");
                return;
            }
            var table = new MutableTable();
            for (var element : cursor.parenthesizedElements()) {
                readElement(element, table);
            }
            tables.put(name, table);
        }

        private static void readElement(List<Token> element, MutableTable table) {
            if (element.isEmpty()) {
                return;
            }
            var first = element.get(0);
            if (first.kind() == Kind.WORD && CONSTRAINT_KEYWORDS.contains(first.value().toUpperCase(Locale.ROOT))) {
                applyConstraint(new Cursor(element), table);
                return;
            }
            var column = readColumn(new Cursor(element));
            table.columns.put(column.name(), column);
        }

        /** Handles [CONSTRAINT name] PRIMARY KEY (a, b); every other constraint has no effect on the comparison. */
        private static void applyConstraint(Cursor cursor, MutableTable table) {
            if (cursor.acceptWord("CONSTRAINT")) {
                cursor.next();
            }
            if (!cursor.acceptWord("PRIMARY")) {
                return;
            }
            cursor.expectWord("KEY");
            if (!cursor.acceptSymbol("(")) {
                return;
            }
            for (var element : cursor.parenthesizedElements()) {
                if (!element.isEmpty()) {
                    table.columns.computeIfPresent(normalize(element.get(0).value()), (name, column) ->
                            new Column(column.name(), column.type(), false, true));
                }
            }
        }

        private static Column readColumn(Cursor cursor) {
            var name = normalize(cursor.identifier());
            var type = readType(cursor);
            boolean nullable = true;
            boolean primaryKey = false;
            int depth = 0;
            while (cursor.hasNext()) {
                var token = cursor.next();
                if (token.isSymbol("(")) {
                    depth++;
                } else if (token.isSymbol(")")) {
                    depth--;
                } else if (depth == 0 && token.isWord("NOT") && cursor.acceptWord("NULL")) {
                    nullable = false;
                } else if (depth == 0 && token.isWord("PRIMARY") && cursor.acceptWord("KEY")) {
                    primaryKey = true;
                    nullable = false;
                }
            }
            return new Column(name, type, nullable, primaryKey);
        }

        private static String readType(Cursor cursor) {
            var type = new StringBuilder(cursor.identifier().toUpperCase(Locale.ROOT));
            var base = type.toString();
            if (base.equals("DOUBLE") && cursor.acceptWord("PRECISION")) {
                type.append(" PRECISION");
            } else if ((base.equals("CHARACTER") || base.equals("CHAR")) && cursor.acceptWord("VARYING")) {
                type.append(" VARYING");
            } else if (base.equals("LONG") && cursor.acceptWord("VARCHAR")) {
                type.append(" VARCHAR");
            }
            var arguments = "";
            if (cursor.acceptSymbol("(")) {
                var text = new StringBuilder();
                for (var element : cursor.parenthesizedElements()) {
                    var parts = new ArrayList<String>();
                    element.forEach(token -> parts.add(token.value()));
                    text.append(text.isEmpty() ? "" : ",").append(String.join(" ", parts));
                }
                arguments = "(" + text + ")";
            }
            if (base.equals("TIMESTAMP") && (cursor.peekWord("WITH") || cursor.peekWord("WITHOUT"))
                    && cursor.isTimeZoneClause()) {
                type.append(" ").append(cursor.next().value().toUpperCase(Locale.ROOT));
                if (cursor.acceptWord("LOCAL")) {
                    type.append(" LOCAL");
                }
                cursor.expectWord("TIME");
                cursor.expectWord("ZONE");
                type.append(" TIME ZONE");
            }
            return type + arguments;
        }

        private void alterTable(Cursor cursor, List<Token> statement, String path) {
            acceptIfExists(cursor);
            var name = cursor.qualifiedName();
            var table = tables.get(name);
            if (table == null) {
                warnings.add(path + ": ALTER TABLE auf unbekannte Tabelle " + name + ".");
                return;
            }
            for (var action : cursor.alterActions()) {
                if (!applyAlter(new Cursor(action), table, name, path)) {
                    warnings.add(path + ": Änderung an " + name + " nicht verstanden (" + preview(action) + ").");
                }
            }
        }

        private boolean applyAlter(Cursor cursor, MutableTable table, String tableName, String path) {
            if (cursor.acceptWord("ADD")) {
                if (cursor.hasNext() && cursor.peek().kind() == Kind.WORD
                        && CONSTRAINT_KEYWORDS.contains(cursor.peek().value().toUpperCase(Locale.ROOT))) {
                    applyConstraint(cursor, table);
                    return true;
                }
                cursor.acceptWord("COLUMN");
                if (cursor.acceptWord("IF")) {
                    cursor.expectWord("NOT");
                    cursor.expectWord("EXISTS");
                }
                var column = readColumn(cursor);
                table.columns.put(column.name(), column);
                return true;
            }
            if (cursor.acceptWord("DROP")) {
                if (cursor.acceptWord("PRIMARY")) {
                    table.columns.replaceAll((name, column) -> new Column(column.name(), column.type(), column.nullable(), false));
                    return true;
                }
                if (cursor.peekWord("CONSTRAINT") || cursor.peekWord("UNIQUE") || cursor.peekWord("FOREIGN")
                        || cursor.peekWord("CHECK")) {
                    return true;
                }
                cursor.acceptWord("COLUMN");
                acceptIfExists(cursor);
                table.columns.remove(normalize(cursor.identifier()));
                return true;
            }
            if (cursor.acceptWord("ALTER") || cursor.acceptWord("MODIFY")) {
                cursor.acceptWord("COLUMN");
                return alterColumn(cursor, table);
            }
            if (cursor.acceptWord("RENAME")) {
                if (cursor.acceptWord("COLUMN")) {
                    var from = normalize(cursor.identifier());
                    cursor.expectWord("TO");
                    var to = normalize(cursor.identifier());
                    var columns = new LinkedHashMap<String, Column>();
                    table.columns.forEach((name, column) -> {
                        var renamed = name.equals(from) ? new Column(to, column.type(), column.nullable(), column.primaryKey()) : column;
                        columns.put(renamed.name(), renamed);
                    });
                    table.columns.clear();
                    table.columns.putAll(columns);
                    return true;
                }
                if (cursor.acceptWord("TO")) {
                    renameTable(tableName, cursor.qualifiedName(), path);
                    return true;
                }
            }
            return false;
        }

        private static boolean alterColumn(Cursor cursor, MutableTable table) {
            var name = normalize(cursor.identifier());
            var column = table.columns.get(name);
            if (column == null) {
                return false;
            }
            Column updated = null;
            if (cursor.acceptWord("SET")) {
                if (cursor.acceptWord("DATA")) {
                    cursor.expectWord("TYPE");
                    updated = new Column(name, readType(cursor), column.nullable(), column.primaryKey());
                } else if (cursor.acceptWord("NOT")) {
                    cursor.expectWord("NULL");
                    updated = new Column(name, column.type(), false, column.primaryKey());
                }
            } else if (cursor.acceptWord("DROP")) {
                if (cursor.acceptWord("NOT")) {
                    cursor.expectWord("NULL");
                    updated = new Column(name, column.type(), true, column.primaryKey());
                }
            } else if (cursor.acceptWord("TYPE")) {
                updated = new Column(name, readType(cursor), column.nullable(), column.primaryKey());
            } else if (cursor.hasNext() && cursor.peek().kind() == Kind.WORD) {
                updated = new Column(name, readType(cursor), column.nullable(), column.primaryKey());
            }
            if (updated == null) {
                return false;
            }
            table.columns.put(name, updated);
            return true;
        }

        private void renameTable(String from, String to, String path) {
            var table = tables.remove(from);
            if (table == null) {
                warnings.add(path + ": Umbenennung einer unbekannten Tabelle " + from + ".");
                return;
            }
            // RENAME TO keeps the schema when the new name is not qualified.
            var target = to.contains(".") || !from.contains(".") ? to : from.substring(0, from.lastIndexOf('.') + 1) + to;
            tables.put(target, table);
        }

        private static void acceptIfExists(Cursor cursor) {
            if (cursor.acceptWord("IF")) {
                cursor.expectWord("EXISTS");
            }
        }
    }

    private static String normalize(String identifier) {
        return identifier.toUpperCase(Locale.ROOT);
    }

    private static final class Cursor {
        private final List<Token> tokens;
        private int position;

        Cursor(List<Token> tokens) {
            this.tokens = tokens;
        }

        boolean hasNext() {
            return position < tokens.size();
        }

        Token peek() {
            return tokens.get(position);
        }

        Token next() {
            return tokens.get(position++);
        }

        boolean peekWord(String word) {
            return hasNext() && peek().isWord(word);
        }

        boolean acceptWord(String word) {
            if (peekWord(word)) {
                position++;
                return true;
            }
            return false;
        }

        void expectWord(String word) {
            if (!acceptWord(word)) {
                throw new IllegalArgumentException("Expected " + word);
            }
        }

        boolean acceptSymbol(String symbol) {
            if (hasNext() && peek().isSymbol(symbol)) {
                position++;
                return true;
            }
            return false;
        }

        /** Tells "WITH TIME ZONE" apart from DB2's "WITH DEFAULT". */
        boolean isTimeZoneClause() {
            for (int offset = 1; offset <= 2 && position + offset < tokens.size(); offset++) {
                if (tokens.get(position + offset).isWord("TIME")) {
                    return true;
                }
            }
            return false;
        }

        String identifier() {
            var token = next();
            if (!token.isIdentifier()) {
                throw new IllegalArgumentException("Expected identifier");
            }
            return token.value();
        }

        String qualifiedName() {
            var name = new StringBuilder(normalize(identifier()));
            while (acceptSymbol(".")) {
                name.append('.').append(normalize(identifier()));
            }
            return name.toString();
        }

        /** Splits the contents of the already opened parenthesis at its top-level commas. */
        List<List<Token>> parenthesizedElements() {
            var elements = new ArrayList<List<Token>>();
            var current = new ArrayList<Token>();
            int depth = 1;
            while (hasNext()) {
                var token = next();
                if (token.isSymbol("(")) {
                    depth++;
                } else if (token.isSymbol(")")) {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                if (depth == 1 && token.isSymbol(",")) {
                    elements.add(current);
                    current = new ArrayList<>();
                } else {
                    current.add(token);
                }
            }
            elements.add(current);
            return elements;
        }

        /** Splits ALTER TABLE at commas and repeated actions, since DB2 lists several ADD COLUMN without a comma. */
        List<List<Token>> alterActions() {
            var actions = new ArrayList<List<Token>>();
            var current = new ArrayList<Token>();
            int depth = 0;
            while (hasNext()) {
                var token = next();
                if (token.isSymbol("(")) {
                    depth++;
                } else if (token.isSymbol(")")) {
                    depth--;
                }
                boolean startsAction = depth == 0 && token.kind() == Kind.WORD
                        && ALTER_ACTIONS.contains(token.value().toUpperCase(Locale.ROOT))
                        && !current.isEmpty() && startsNewAction(current);
                if (depth == 0 && token.isSymbol(",")) {
                    actions.add(current);
                    current = new ArrayList<>();
                    continue;
                }
                if (startsAction) {
                    actions.add(current);
                    current = new ArrayList<>();
                }
                current.add(token);
            }
            if (!current.isEmpty()) {
                actions.add(current);
            }
            // Snowflake repeats only the first column's keyword: "ADD COLUMN a INT, b INT".
            for (int i = 1; i < actions.size(); i++) {
                var action = actions.get(i);
                if (!action.isEmpty() && !(action.get(0).kind() == Kind.WORD
                        && ALTER_ACTIONS.contains(action.get(0).value().toUpperCase(Locale.ROOT)))) {
                    var previous = actions.get(i - 1);
                    if (!previous.isEmpty() && previous.get(0).isWord("ADD")) {
                        var prefixed = new ArrayList<Token>();
                        prefixed.add(previous.get(0));
                        prefixed.addAll(action);
                        actions.set(i, prefixed);
                    }
                }
            }
            return actions;
        }

        /** Inside "ALTER COLUMN x DROP NOT NULL" or "SET ..." the keyword belongs to the current action. */
        private static boolean startsNewAction(List<Token> current) {
            var first = current.get(0);
            return !(first.isWord("ALTER") || first.isWord("MODIFY"));
        }
    }
}
