package de.opeldus.datacontract.catalog;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty("app.catalog.demo")
public class DemoCatalogProvider implements CatalogProvider {

    @Override
    public List<Dataset> fetchDatasets() {
        return List.of(
                new Dataset("db2.SALES.KUNDE", "urn:li:dataset:(urn:li:dataPlatform:db2,SALES.KUNDE,PROD)", "db2",
                        List.of(new Column("KUNDEN_NR", "CHAR(10)", false, "Kundennummer"),
                                new Column("NAME", "VARCHAR(100)", false, "Name des Kunden"),
                                new Column("GEBURTSDATUM", "DATE", true, "Geburtsdatum"),
                                new Column("UMSATZ", "DECIMAL(18,2)", true, "Jahresumsatz in EUR"))),
                new Dataset("db2.SALES.AUFTRAG", "urn:li:dataset:(urn:li:dataPlatform:db2,SALES.AUFTRAG,PROD)", "db2",
                        List.of(new Column("AUFTRAG_ID", "BIGINT", false, "Auftragsnummer"),
                                new Column("KUNDEN_NR", "CHAR(10)", false, "Kundennummer"),
                                new Column("ANGELEGT_AM", "TIMESTAMP", false, "Anlagezeitpunkt"))),
                new Dataset("snowflake.DWH.CORE.DIM_KUNDE",
                        "urn:li:dataset:(urn:li:dataPlatform:snowflake,DWH.CORE.DIM_KUNDE,PROD)", "snowflake",
                        List.of(new Column("KUNDEN_ID", "NUMBER(38,0)", false, "Technischer Schlüssel"),
                                new Column("KUNDEN_NR", "VARCHAR(10)", false, "Kundennummer"))));
    }
}
