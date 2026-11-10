package de.opeldus.datacontract.etl;

import static org.assertj.core.api.Assertions.assertThat;

import de.opeldus.datacontract.etl.EtlModel.TableAccess;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlAnalyzerTest {

    private static List<String> reads(String sql) {
        return SqlAnalyzer.tables(sql).stream().filter(t -> t.access() == EtlModel.Access.READ).map(TableAccess::name).toList();
    }

    private static List<String> writes(String sql) {
        return SqlAnalyzer.tables(sql).stream().filter(t -> t.access() == EtlModel.Access.WRITE).map(TableAccess::name).toList();
    }

    @Test
    void findsTablesInCommaListsJoinsAndSubqueries() {
        assertThat(reads("SELECT * FROM a x, b AS y JOIN c ON y.i = c.i WHERE x.i IN (SELECT i FROM d)"))
                .containsExactly("A", "B", "C", "D");
        assertThat(reads("SELECT 1 FROM (SELECT i FROM inner_t) q, outer_t")).containsExactly("INNER_T", "OUTER_T");
    }

    @Test
    void keepsJobParametersAndSkipsDataStageSpecifics() {
        assertThat(reads("SELECT * FROM #SCHEMA#.KUNDE k WHERE k.ID = ORCHESTRATE.ID")).containsExactly("#SCHEMA#.KUNDE");
        assertThat(reads("SELECT CURRENT DATE FROM SYSIBM.SYSDUMMY1")).isEmpty();
    }

    @Test
    void ignoresWhatOnlyLooksLikeATable() {
        assertThat(reads("SELECT EXTRACT(YEAR FROM d), 'FROM fake' FROM echt -- FROM kommentar\n/* JOIN auch */")).containsExactly("ECHT");
        assertThat(reads("WITH cte AS (SELECT i FROM basis), b (x) AS (SELECT x FROM cte) SELECT * FROM cte JOIN b ON 1=1"))
                .containsExactly("BASIS");
    }

    @Test
    void separatesReadingFromWriting() {
        var sql = "INSERT INTO z.ziel (a) SELECT a FROM z.quelle";
        assertThat(writes(sql)).containsExactly("Z.ZIEL");
        assertThat(reads(sql)).containsExactly("Z.QUELLE");
        assertThat(writes("UPDATE t SET a = (SELECT MAX(a) FROM u)")).containsExactly("T");
        assertThat(reads("UPDATE t SET a = (SELECT MAX(a) FROM u)")).containsExactly("U");
        assertThat(writes("DELETE FROM t WHERE 1=1; TRUNCATE TABLE v")).containsExactly("T", "V");
        assertThat(writes("SELECT * FROM t FOR UPDATE")).isEmpty();
    }

    @Test
    void understandsMerge() {
        var sql = "MERGE INTO ziel z USING quelle q ON z.i = q.i WHEN MATCHED THEN UPDATE SET z.a = q.a WHEN NOT MATCHED THEN INSERT (i) VALUES (q.i)";
        assertThat(writes(sql)).containsExactly("ZIEL");
        assertThat(reads(sql)).containsExactly("QUELLE");
    }
}
