package de.opeldus.datacontract.imports;

import static de.opeldus.datacontract.common.ApiExceptions.badRequest;

import de.opeldus.datacontract.mapping.MappingDtos.RuleDto;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

/** Reads existing mapping documents; the header row is detected by column names, not by position. */
@Component
public class ExcelMappingImporter {

    private static final int HEADER_SEARCH_ROWS = 20;
    private static final int MAX_RULES = 10_000;
    private static final Set<String> TRUE_VALUES = Set.of("x", "ja", "j", "yes", "y", "true", "wahr", "1");

    private enum Field { SOURCE_DATASET, SOURCE_COLUMN, TARGET_COLUMN, TARGET_TYPE, TRANSFORMATION, REQUIRED,
        PRIMARY_KEY, DESCRIPTION, REQUIREMENT }

    private static final Map<String, Field> ALIASES = Map.ofEntries(
            Map.entry("quelle", Field.SOURCE_DATASET),
            Map.entry("quelltabelle", Field.SOURCE_DATASET),
            Map.entry("quellsystem", Field.SOURCE_DATASET),
            Map.entry("sourcetable", Field.SOURCE_DATASET),
            Map.entry("sourcedataset", Field.SOURCE_DATASET),
            Map.entry("source", Field.SOURCE_DATASET),
            Map.entry("quellfeld", Field.SOURCE_COLUMN),
            Map.entry("quellspalte", Field.SOURCE_COLUMN),
            Map.entry("sourcecolumn", Field.SOURCE_COLUMN),
            Map.entry("sourcefield", Field.SOURCE_COLUMN),
            Map.entry("zielfeld", Field.TARGET_COLUMN),
            Map.entry("zielspalte", Field.TARGET_COLUMN),
            Map.entry("targetcolumn", Field.TARGET_COLUMN),
            Map.entry("targetfield", Field.TARGET_COLUMN),
            Map.entry("typ", Field.TARGET_TYPE),
            Map.entry("zieltyp", Field.TARGET_TYPE),
            Map.entry("datentyp", Field.TARGET_TYPE),
            Map.entry("targettype", Field.TARGET_TYPE),
            Map.entry("datatype", Field.TARGET_TYPE),
            Map.entry("transformation", Field.TRANSFORMATION),
            Map.entry("transformationsregel", Field.TRANSFORMATION),
            Map.entry("regel", Field.TRANSFORMATION),
            Map.entry("logik", Field.TRANSFORMATION),
            Map.entry("pflicht", Field.REQUIRED),
            Map.entry("pflichtfeld", Field.REQUIRED),
            Map.entry("required", Field.REQUIRED),
            Map.entry("notnull", Field.REQUIRED),
            Map.entry("pk", Field.PRIMARY_KEY),
            Map.entry("primarykey", Field.PRIMARY_KEY),
            Map.entry("primaerschluessel", Field.PRIMARY_KEY),
            Map.entry("beschreibung", Field.DESCRIPTION),
            Map.entry("description", Field.DESCRIPTION),
            Map.entry("anforderung", Field.REQUIREMENT),
            Map.entry("anforderungskey", Field.REQUIREMENT),
            Map.entry("requirement", Field.REQUIREMENT),
            Map.entry("requirementkey", Field.REQUIREMENT),
            Map.entry("jira", Field.REQUIREMENT));

    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

    public ParsedWorkbook parse(InputStream input) {
        try (var workbook = WorkbookFactory.create(input)) {
            for (var sheet : workbook) {
                var header = findHeader(sheet);
                if (header != null) {
                    return readRows(sheet, header);
                }
            }
        } catch (IOException | RuntimeException exception) {
            throw badRequest("Die Excel-Datei konnte nicht gelesen werden.");
        }
        throw badRequest("Keine Kopfzeile gefunden. Erwartet wird mindestens eine Spalte wie 'Zielfeld' "
                + "und eine weitere wie 'Quellfeld' oder 'Datentyp'.");
    }

    private Header findHeader(Sheet sheet) {
        for (int index = sheet.getFirstRowNum(); index >= 0 && index < HEADER_SEARCH_ROWS; index++) {
            var row = sheet.getRow(index);
            if (row == null) {
                continue;
            }
            var columns = new EnumMap<Field, Integer>(Field.class);
            var ignored = new ArrayList<String>();
            for (var cell : row) {
                var title = text(cell);
                if (title.isEmpty()) {
                    continue;
                }
                var field = ALIASES.get(normalize(title));
                if (field != null) {
                    columns.putIfAbsent(field, cell.getColumnIndex());
                } else {
                    ignored.add(title);
                }
            }
            if (columns.containsKey(Field.TARGET_COLUMN) && columns.size() >= 2) {
                return new Header(index, columns, ignored);
            }
        }
        return null;
    }

    private ParsedWorkbook readRows(Sheet sheet, Header header) {
        var rules = new ArrayList<RuleDto>();
        int skipped = 0;
        for (int index = header.rowIndex() + 1; index <= sheet.getLastRowNum(); index++) {
            var row = sheet.getRow(index);
            if (row == null || isBlank(row, header)) {
                continue;
            }
            if (value(row, header, Field.TARGET_COLUMN).isEmpty()) {
                skipped++;
                continue;
            }
            if (rules.size() >= MAX_RULES) {
                throw badRequest("Die Datei enthält mehr als " + MAX_RULES + " Regeln.");
            }
            rules.add(new RuleDto(null,
                    limit(value(row, header, Field.SOURCE_DATASET), 300),
                    limit(value(row, header, Field.SOURCE_COLUMN), 200),
                    limit(value(row, header, Field.TARGET_COLUMN), 200),
                    limit(value(row, header, Field.TARGET_TYPE), 100),
                    isTrue(value(row, header, Field.REQUIRED)),
                    isTrue(value(row, header, Field.PRIMARY_KEY)),
                    limit(value(row, header, Field.TRANSFORMATION), 4000),
                    limit(value(row, header, Field.DESCRIPTION), 4000),
                    limit(value(row, header, Field.REQUIREMENT), 50)));
        }
        return new ParsedWorkbook(rules, skipped, header.ignoredColumns());
    }

    private boolean isBlank(Row row, Header header) {
        return header.columns().keySet().stream().allMatch(field -> value(row, header, field).isEmpty());
    }

    private String value(Row row, Header header, Field field) {
        var column = header.columns().get(field);
        return column == null ? "" : text(row.getCell(column));
    }

    /** Uses the value Excel last calculated, so formulas are never evaluated on the server. */
    private String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() != CellType.FORMULA) {
            return formatter.formatCellValue(cell).trim();
        }
        return switch (cell.getCachedFormulaResultType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    private static String normalize(String title) {
        return title.toLowerCase(Locale.GERMAN)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replaceAll("[^a-z0-9]", "");
    }

    private static boolean isTrue(String value) {
        return TRUE_VALUES.contains(value.toLowerCase(Locale.ROOT));
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record Header(int rowIndex, Map<Field, Integer> columns, List<String> ignoredColumns) {}

    public record ParsedWorkbook(List<RuleDto> rules, int skippedRows, List<String> ignoredColumns) {}
}
