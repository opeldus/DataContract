package de.opeldus.datacontract.validation;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** A physical column type broken down far enough to compare two types across DB2 and Snowflake. */
public record ColumnType(String base, TypeFamily family, Integer length, Integer scale) {

    private static final Pattern PATTERN = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_]*(?: [A-Za-z]+)*)\\s*(?:\\(\\s*(\\d+)[^,)]*(?:,\\s*(\\d+)\\s*)?\\))?.*$");

    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("INT", "INTEGER"),
            Map.entry("DEC", "DECIMAL"),
            Map.entry("NUMERIC", "DECIMAL"),
            Map.entry("NUMBER", "DECIMAL"),
            Map.entry("CHARACTER", "CHAR"),
            Map.entry("CHARACTER VARYING", "VARCHAR"),
            Map.entry("CHAR VARYING", "VARCHAR"),
            Map.entry("VARCHAR2", "VARCHAR"),
            Map.entry("STRING", "VARCHAR"),
            Map.entry("TEXT", "VARCHAR"),
            Map.entry("DOUBLE PRECISION", "DOUBLE"),
            Map.entry("DATETIME", "TIMESTAMP"),
            Map.entry("TIMESTAMP WITHOUT TIME ZONE", "TIMESTAMP"));

    public enum Difference { SAME, VARIANT, WIDENED, NARROWED, INCOMPATIBLE }

    /** The family is null for types that are not known; such types only compare by name. */
    public static ColumnType parse(String type) {
        var matcher = PATTERN.matcher(type == null ? "" : type);
        if (!matcher.matches()) {
            return new ColumnType(type == null ? "" : type.trim().toUpperCase(Locale.ROOT), null, null, null);
        }
        var name = matcher.group(1).toUpperCase(Locale.ROOT).trim();
        return new ColumnType(SYNONYMS.getOrDefault(name, name), TypeFamily.of(type).orElse(null),
                number(matcher.group(2)), number(matcher.group(3)));
    }

    /** Describes the step from this type to {@code other}, seen by someone reading the column. */
    public Difference compareTo(ColumnType other) {
        if (family == null || other.family == null) {
            return base.equals(other.base) ? Difference.SAME : Difference.VARIANT;
        }
        if (family != other.family) {
            if (family == TypeFamily.INTEGER && other.family == TypeFamily.NUMBER) {
                return Difference.WIDENED;
            }
            if (family == TypeFamily.NUMBER && other.family == TypeFamily.INTEGER) {
                return Difference.NARROWED;
            }
            return Difference.INCOMPATIBLE;
        }
        var sized = compareSize(other);
        if (sized != Difference.SAME) {
            return sized;
        }
        return base.equals(other.base) ? Difference.SAME : Difference.VARIANT;
    }

    private Difference compareSize(ColumnType other) {
        if (length == null || other.length == null || Objects.equals(length, other.length) && Objects.equals(scale, other.scale)) {
            return Difference.SAME;
        }
        int scaleA = scale == null ? 0 : scale;
        int scaleB = other.scale == null ? 0 : other.scale;
        int wholeA = length - scaleA;
        int wholeB = other.length - scaleB;
        return wholeB >= wholeA && scaleB >= scaleA ? Difference.WIDENED : Difference.NARROWED;
    }

    private static Integer number(String digits) {
        return digits == null || digits.length() > 9 ? null : Integer.valueOf(digits);
    }
}
