package de.opeldus.datacontract.validation;

import static org.assertj.core.api.Assertions.assertThat;

import de.opeldus.datacontract.validation.ColumnType.Difference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ColumnTypeTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "VARCHAR(10)    | varchar(10)      | SAME",
            "INT            | INTEGER          | SAME",
            "NUMERIC(18,2)  | NUMBER(18,2)     | SAME",
            "STRING         | VARCHAR          | SAME",
            "VARCHAR2(10)   | VARCHAR(10)      | SAME",
            "VARCHAR        | VARCHAR(10)      | SAME",
            "VARCHAR(10)    | VARCHAR(20)      | WIDENED",
            "VARCHAR(20)    | VARCHAR(10)      | NARROWED",
            "DECIMAL(10,2)  | DECIMAL(12,2)    | WIDENED",
            "DECIMAL(12,2)  | DECIMAL(10,2)    | NARROWED",
            "DECIMAL(10,2)  | DECIMAL(10,4)    | NARROWED",
            "INTEGER        | DECIMAL(18,2)    | WIDENED",
            "DECIMAL(18,2)  | INTEGER          | NARROWED",
            "NUMBER(38,0)   | INTEGER          | VARIANT",
            "CHAR(10)       | VARCHAR(10)      | VARIANT",
            "DATE           | TIMESTAMP        | INCOMPATIBLE",
            "VARCHAR(10)    | INTEGER          | INCOMPATIBLE",
            "GEOGRAPHY      | GEOGRAPHY        | SAME",
            "GEOGRAPHY      | GEOMETRY         | VARIANT",
    })
    void comparesTypesAcrossDialects(String from, String to, Difference expected) {
        assertThat(ColumnType.parse(from).compareTo(ColumnType.parse(to))).isEqualTo(expected);
    }
}
