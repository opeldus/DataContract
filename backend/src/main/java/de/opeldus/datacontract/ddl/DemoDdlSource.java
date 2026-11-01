package de.opeldus.datacontract.ddl;

import de.opeldus.datacontract.ddl.DdlSchema.DdlFile;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty("app.ddl.demo")
public class DemoDdlSource implements DdlSource {

    private static final String DIM_KUNDE = """
            CREATE TABLE DWH.CORE.DIM_KUNDE (
                KUNDEN_NR VARCHAR(10) NOT NULL,
                UMSATZ_EUR NUMBER(18,2),
                GEBURTSDATUM DATE,
                PRIMARY KEY (KUNDEN_NR)
            ) CLUSTER BY (KUNDEN_NR);
            """;

    @Override
    public Snapshot load() {
        return new Snapshot("demo", List.of(new DdlFile("V1__dim_kunde.sql", DIM_KUNDE)));
    }
}
