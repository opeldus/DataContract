package de.opeldus.datacontract.ddl;

import static org.assertj.core.api.Assertions.assertThat;

import de.opeldus.datacontract.ddl.DdlSchema.Column;
import de.opeldus.datacontract.ddl.DdlSchema.DdlFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class DdlParserTest {

    private final DdlParser parser = new DdlParser();

    private DdlSchema parse(String... files) {
        var ddl = new java.util.ArrayList<DdlFile>();
        for (int i = 0; i < files.length; i++) {
            ddl.add(new DdlFile("V" + (i + 1) + "__x.sql", files[i]));
        }
        return parser.parse(ddl);
    }

    private static Column column(DdlSchema schema, String table, String name) {
        return schema.tables().get(table).columns().stream()
                .filter(column -> column.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " not found in " + schema.tables().get(table)));
    }

    @Test
    void readsDb2TableWithVendorClauses() {
        var schema = parse("""
                -- Kundenstamm
                CREATE TABLE SALES.KUNDE (
                    KUNDEN_NR CHAR(10) NOT NULL,
                    NAME VARCHAR(100) FOR BIT DATA,
                    UMSATZ DECIMAL(18, 2) NOT NULL WITH DEFAULT 0,
                    ANGELEGT TIMESTAMP(6) NOT NULL GENERATED ALWAYS FOR EACH ROW ON UPDATE AS ROW CHANGE TIMESTAMP,
                    NOTIZ VARCHAR(50) DEFAULT 'a,b;(c' CHECK (NOTIZ <> ''),
                    CONSTRAINT PK_KUNDE PRIMARY KEY (KUNDEN_NR)
                ) IN TS_DATA ORGANIZE BY ROW COMPRESS YES;
                """);

        assertThat(schema.warnings()).isEmpty();
        var key = column(schema, "SALES.KUNDE", "KUNDEN_NR");
        assertThat(key.type()).isEqualTo("CHAR(10)");
        assertThat(key.nullable()).isFalse();
        assertThat(key.primaryKey()).isTrue();
        assertThat(column(schema, "SALES.KUNDE", "NAME").type()).isEqualTo("VARCHAR(100)");
        assertThat(column(schema, "SALES.KUNDE", "UMSATZ").type()).isEqualTo("DECIMAL(18,2)");
        assertThat(column(schema, "SALES.KUNDE", "UMSATZ").nullable()).isFalse();
        assertThat(column(schema, "SALES.KUNDE", "NOTIZ").nullable()).isTrue();
        assertThat(schema.tables().get("SALES.KUNDE").columns()).hasSize(5);
    }

    @Test
    void readsSnowflakeTableWithQuotedNamesAndOptions() {
        var schema = parse("""
                CREATE OR REPLACE TRANSIENT TABLE IF NOT EXISTS DWH.CORE."Dim_Kunde" (
                    "Kunden_Nr" VARCHAR(10) PRIMARY KEY COMMENT 'Nummer; eindeutig',
                    BETRAG NUMBER(38,0),
                    STAND TIMESTAMP_NTZ(9) NOT NULL,
                    AKTIV BOOLEAN DEFAULT TRUE,
                    ZEIT TIMESTAMP WITH TIME ZONE,
                    PREIS DOUBLE PRECISION
                ) CLUSTER BY (STAND) COMMENT = 'Kunden' COPY GRANTS;
                """);

        assertThat(schema.warnings()).isEmpty();
        assertThat(column(schema, "DWH.CORE.DIM_KUNDE", "KUNDEN_NR").primaryKey()).isTrue();
        assertThat(column(schema, "DWH.CORE.DIM_KUNDE", "STAND").type()).isEqualTo("TIMESTAMP_NTZ(9)");
        assertThat(column(schema, "DWH.CORE.DIM_KUNDE", "ZEIT").type()).isEqualTo("TIMESTAMP WITH TIME ZONE");
        assertThat(column(schema, "DWH.CORE.DIM_KUNDE", "PREIS").type()).isEqualTo("DOUBLE PRECISION");
    }

    @Test
    void db2WithDefaultIsNotMistakenForTimeZone() {
        var schema = parse("CREATE TABLE T (A TIMESTAMP WITH DEFAULT CURRENT TIMESTAMP NOT NULL);");

        assertThat(column(schema, "T", "A").type()).isEqualTo("TIMESTAMP");
        assertThat(column(schema, "T", "A").nullable()).isFalse();
    }

    @Test
    void appliesMigrationsInVersionOrderNotAlphabetically() {
        var schema = parser.parse(List.of(
                new DdlFile("V10__drop.sql", "ALTER TABLE T DROP COLUMN B;"),
                new DdlFile("V2__add.sql", "ALTER TABLE T ADD COLUMN B INT;"),
                new DdlFile("V1__create.sql", "CREATE TABLE T (A INT);")));

        assertThat(schema.warnings()).isEmpty();
        assertThat(schema.tables().get("T").columns()).extracting(Column::name).containsExactly("A");
    }

    @Test
    void appliesAlterStatementsOfBothDialects() {
        var schema = parse(
                "CREATE TABLE T (A VARCHAR(5), B INT NOT NULL, C INT, D INT, E INT);",
                """
                ALTER TABLE T ALTER COLUMN A SET DATA TYPE VARCHAR(20);
                ALTER TABLE T ALTER COLUMN B DROP NOT NULL;
                ALTER TABLE T ALTER COLUMN C SET NOT NULL;
                ALTER TABLE T RENAME COLUMN D TO D2;
                ALTER TABLE T DROP COLUMN E;
                ALTER TABLE T ADD COLUMN F INT ADD COLUMN G INT NOT NULL;
                ALTER TABLE T ADD COLUMN H INT, I DATE;
                ALTER TABLE T ADD PRIMARY KEY (C);
                """);

        assertThat(schema.warnings()).isEmpty();
        assertThat(column(schema, "T", "A").type()).isEqualTo("VARCHAR(20)");
        assertThat(column(schema, "T", "B").nullable()).isTrue();
        assertThat(column(schema, "T", "C").nullable()).isFalse();
        assertThat(column(schema, "T", "C").primaryKey()).isTrue();
        assertThat(column(schema, "T", "G").nullable()).isFalse();
        assertThat(schema.tables().get("T").columns()).extracting(Column::name)
                .containsExactly("A", "B", "C", "D2", "F", "G", "H", "I");
    }

    @Test
    void handlesDroppedAndRenamedTables() {
        var schema = parse(
                "CREATE TABLE S.A (X INT); CREATE TABLE S.B (X INT); CREATE TABLE S.C (X INT);",
                "DROP TABLE S.A; RENAME TABLE S.B TO B2; ALTER TABLE S.C RENAME TO C2;");

        assertThat(schema.tables().keySet()).containsExactly("S.B2", "S.C2");
    }

    @Test
    void db2AtSignEndsStatementsButStaysPartOfNames() {
        var schema = parse("""
                --#SET TERMINATOR @
                CREATE TABLE T (A INT)@
                CREATE TABLE U (B@X INT)@
                """);

        assertThat(schema.tables().keySet()).containsExactly("T", "U");
        assertThat(column(schema, "U", "B@X").type()).isEqualTo("INT");
    }

    @Test
    void reportsWhatItCannotReadAndContinues() {
        var schema = parse(
                """
                CREATE TABLE AUS_SELECT AS SELECT * FROM X;
                ALTER TABLE UNBEKANNT ADD COLUMN A INT;
                CREATE TABLE T (A INT);
                ALTER TABLE T SPLIT PARTITION P1;
                CREATE TABLE @@@;
                CREATE TABLE OK (A INT);
                """);

        assertThat(schema.tables().keySet()).containsExactly("OK", "T");
        assertThat(schema.warnings()).hasSize(4);
        assertThat(schema.warnings().get(0)).contains("V1__x.sql").contains("AUS_SELECT");
    }

    @Test
    void ignoresOtherStatementsAndCommentsThatLookLikeDdl() {
        var schema = parse("""
                /* CREATE TABLE ALT (A INT); */
                -- CREATE TABLE ALT2 (A INT);
                CREATE INDEX I ON T (A);
                CREATE VIEW V AS SELECT 1;
                INSERT INTO T VALUES ('CREATE TABLE X (A INT);');
                GRANT SELECT ON T TO R;
                """);

        assertThat(schema.tables()).isEmpty();
        assertThat(schema.warnings()).isEmpty();
    }

    @Test
    void unterminatedInputDoesNotHang() {
        var schema = parse("CREATE TABLE T (A VARCHAR(10), B 'offen");

        assertThat(schema.tables()).isNotNull();
    }
}
