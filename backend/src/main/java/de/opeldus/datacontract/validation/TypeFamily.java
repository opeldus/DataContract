package de.opeldus.datacontract.validation;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Groups DB2 and Snowflake column types into the logical types of ODCS. */
public enum TypeFamily {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    DATE("date"),
    TIME("time"),
    TIMESTAMP("timestamp"),
    OBJECT("object"),
    ARRAY("array");

    private static final Pattern TYPE = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*(?: [A-Za-z]+)*)\\s*(?:\\(([^)]*)\\))?.*$");
    private static final Pattern ZERO_SCALE = Pattern.compile("^\\s*\\d+\\s*,\\s*0\\s*$");

    private static final Map<String, TypeFamily> TYPES = Map.ofEntries(
            Map.entry("CHAR", STRING), Map.entry("CHARACTER", STRING), Map.entry("VARCHAR", STRING),
            Map.entry("VARCHAR2", STRING), Map.entry("CHARACTER VARYING", STRING), Map.entry("GRAPHIC", STRING),
            Map.entry("VARGRAPHIC", STRING), Map.entry("CLOB", STRING), Map.entry("DBCLOB", STRING),
            Map.entry("LONG VARCHAR", STRING), Map.entry("CHAR VARYING", STRING),
            Map.entry("TEXT", STRING), Map.entry("STRING", STRING),
            Map.entry("SMALLINT", INTEGER), Map.entry("INT", INTEGER), Map.entry("INTEGER", INTEGER),
            Map.entry("BIGINT", INTEGER), Map.entry("TINYINT", INTEGER),
            Map.entry("DECIMAL", NUMBER), Map.entry("DEC", NUMBER), Map.entry("NUMERIC", NUMBER),
            Map.entry("NUMBER", NUMBER), Map.entry("DECFLOAT", NUMBER), Map.entry("REAL", NUMBER),
            Map.entry("FLOAT", NUMBER), Map.entry("DOUBLE", NUMBER), Map.entry("DOUBLE PRECISION", NUMBER),
            Map.entry("BOOLEAN", BOOLEAN),
            Map.entry("DATE", DATE),
            Map.entry("TIME", TIME),
            Map.entry("TIMESTAMP", TIMESTAMP), Map.entry("TIMESTAMP_NTZ", TIMESTAMP),
            Map.entry("TIMESTAMP_LTZ", TIMESTAMP), Map.entry("TIMESTAMP_TZ", TIMESTAMP),
            Map.entry("DATETIME", TIMESTAMP), Map.entry("TIMESTAMP WITH TIME ZONE", TIMESTAMP),
            Map.entry("TIMESTAMP WITHOUT TIME ZONE", TIMESTAMP), Map.entry("TIMESTAMP WITH LOCAL TIME ZONE", TIMESTAMP),
            Map.entry("VARIANT", OBJECT), Map.entry("OBJECT", OBJECT),
            Map.entry("ARRAY", ARRAY));

    private final String logicalType;

    TypeFamily(String logicalType) {
        this.logicalType = logicalType;
    }

    public String logicalType() {
        return logicalType;
    }

    public static Optional<TypeFamily> of(String physicalType) {
        if (physicalType == null) {
            return Optional.empty();
        }
        var matcher = TYPE.matcher(physicalType);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        var name = matcher.group(1).toUpperCase(Locale.ROOT).trim();
        var family = TYPES.get(name);
        if (family == NUMBER && matcher.group(2) != null && ZERO_SCALE.matcher(matcher.group(2)).matches()) {
            return Optional.of(INTEGER);
        }
        return Optional.ofNullable(family);
    }

    /** Whether a value converts without a transformation and without losing information. */
    public boolean acceptsWithoutTransformation(TypeFamily source) {
        return this == source
                || this == STRING
                || (this == NUMBER && source == INTEGER)
                || (this == TIMESTAMP && source == DATE);
    }
}
