import type { ClipboardEvent } from "react";
import { api, type Rule, type ValidationIssue } from "./api";
import { Autocomplete } from "./Autocomplete";
import { isTruthy, parseClipboard } from "./paste";

type Field = keyof Omit<Rule, "id">;

/** Column order of the grid; it is also the order in which cells pasted from Excel are applied. */
const COLUMNS: { field: Field; title: string }[] = [
  { field: "sourceDataset", title: "Quelle" },
  { field: "sourceColumn", title: "Quellfeld" },
  { field: "targetColumn", title: "Zielfeld" },
  { field: "targetType", title: "Zieltyp" },
  { field: "required", title: "Pflicht" },
  { field: "primaryKey", title: "PK" },
  { field: "transformation", title: "Transformation" },
  { field: "description", title: "Beschreibung" },
  { field: "requirementKey", title: "Anforderung" },
];

const COMMON_TYPES = ["VARCHAR(100)", "CHAR(10)", "INTEGER", "BIGINT", "DECIMAL(18,2)", "NUMBER(38,0)", "BOOLEAN",
  "DATE", "TIME", "TIMESTAMP", "TIMESTAMP_NTZ", "VARIANT"];

export function emptyRule(): Rule {
  return {
    id: null,
    sourceDataset: null,
    sourceColumn: null,
    targetColumn: null,
    targetType: null,
    required: false,
    primaryKey: false,
    transformation: null,
    description: null,
    requirementKey: null,
  };
}

interface Props {
  rules: Rule[];
  issues: ValidationIssue[];
  editable: boolean;
  selectedRuleId: string | null;
  onChange: (rules: Rule[]) => void;
  onSelectRule: (ruleId: string | null) => void;
}

export function RuleGrid({ rules, issues, editable, selectedRuleId, onChange, onSelectRule }: Props) {
  function setField<F extends Field>(index: number, field: F, value: Rule[F]) {
    onChange(rules.map((rule, i) => (i === index ? { ...rule, [field]: value } : rule)));
  }

  /** A single value behaves like normal typing; only a multi-cell clipboard fills the grid. */
  function handlePaste(event: ClipboardEvent<HTMLInputElement>, rowIndex: number, field: Field) {
    const rows = parseClipboard(event.clipboardData.getData("text"));
    if (rows.length <= 1 && (rows[0]?.length ?? 0) <= 1) {
      return;
    }
    event.preventDefault();
    const startColumn = COLUMNS.findIndex((column) => column.field === field);
    const next = [...rules];
    rows.forEach((cells, rowOffset) => {
      const targetIndex = rowIndex + rowOffset;
      const rule = { ...(next[targetIndex] ?? emptyRule()) };
      cells.forEach((cell, cellOffset) => {
        const column = COLUMNS[startColumn + cellOffset];
        if (!column) {
          return;
        }
        if (column.field === "required" || column.field === "primaryKey") {
          rule[column.field] = isTruthy(cell);
        } else {
          rule[column.field] = cell.trim() || null;
        }
      });
      next[targetIndex] = rule;
    });
    onChange(next);
  }

  function issuesFor(rule: Rule, field: Field) {
    return issues.filter((issue) => issue.ruleId !== null && issue.ruleId === rule.id && issue.field === field);
  }

  function cellClass(rule: Rule, field: Field) {
    const cellIssues = issuesFor(rule, field);
    if (cellIssues.some((issue) => issue.severity === "ERROR")) {
      return "cell-error";
    }
    return cellIssues.length > 0 ? "cell-warning" : undefined;
  }

  function cellTitle(rule: Rule, field: Field) {
    return issuesFor(rule, field).map((issue) => issue.message).join("\n") || undefined;
  }

  function textCell(rule: Rule, index: number, field: Exclude<Field, "required" | "primaryKey">, label: string) {
    return (
      <td className={cellClass(rule, field)} title={cellTitle(rule, field)}>
        <input
          aria-label={`${label}, Zeile ${index + 1}`}
          disabled={!editable}
          value={rule[field] ?? ""}
          onChange={(event) => setField(index, field, event.target.value || null)}
          onPaste={(event) => handlePaste(event, index, field)}
        />
      </td>
    );
  }

  return (
    <div className="grid-wrapper">
      <table className="rule-grid">
        <thead>
          <tr>
            <th aria-label="Zeile">#</th>
            {COLUMNS.map((column) => (
              <th key={column.field}>{column.title}</th>
            ))}
            <th aria-label="Aktionen" />
          </tr>
        </thead>
        <tbody>
          {rules.map((rule, index) => (
            <tr
              key={rule.id ?? `new-${index}`}
              className={rule.id !== null && rule.id === selectedRuleId ? "selected" : undefined}
              onClick={() => onSelectRule(rule.id)}
            >
              <td className="row-number">{index + 1}</td>
              <td className={cellClass(rule, "sourceDataset")} title={cellTitle(rule, "sourceDataset")}>
                <Autocomplete
                  label={`Quelle, Zeile ${index + 1}`}
                  disabled={!editable}
                  value={rule.sourceDataset ?? ""}
                  search={async (query) =>
                    (await api.searchDatasets(query)).map((dataset) => ({
                      value: dataset.name,
                      hint: dataset.platform,
                    }))
                  }
                  onChange={(value) => setField(index, "sourceDataset", value || null)}
                  onPaste={(event) => handlePaste(event, index, "sourceDataset")}
                />
              </td>
              <td className={cellClass(rule, "sourceColumn")} title={cellTitle(rule, "sourceColumn")}>
                <Autocomplete
                  label={`Quellfeld, Zeile ${index + 1}`}
                  disabled={!editable}
                  value={rule.sourceColumn ?? ""}
                  search={async (query) =>
                    rule.sourceDataset
                      ? (await api.searchColumns(rule.sourceDataset, query)).map((column) => ({
                          value: column.name,
                          hint: column.nativeType ?? undefined,
                        }))
                      : []
                  }
                  onChange={(value) => setField(index, "sourceColumn", value || null)}
                  onPaste={(event) => handlePaste(event, index, "sourceColumn")}
                />
              </td>
              {textCell(rule, index, "targetColumn", "Zielfeld")}
              <td className={cellClass(rule, "targetType")} title={cellTitle(rule, "targetType")}>
                <Autocomplete
                  label={`Zieltyp, Zeile ${index + 1}`}
                  disabled={!editable}
                  value={rule.targetType ?? ""}
                  search={async (query) =>
                    COMMON_TYPES.filter((type) => type.includes(query.toUpperCase())).map((value) => ({ value }))
                  }
                  onChange={(value) => setField(index, "targetType", value || null)}
                  onPaste={(event) => handlePaste(event, index, "targetType")}
                />
              </td>
              <td className={`check ${cellClass(rule, "required") ?? ""}`} title={cellTitle(rule, "required")}>
                <input
                  type="checkbox"
                  aria-label={`Pflicht, Zeile ${index + 1}`}
                  disabled={!editable}
                  checked={rule.required}
                  onChange={(event) => setField(index, "required", event.target.checked)}
                />
              </td>
              <td className="check">
                <input
                  type="checkbox"
                  aria-label={`Primärschlüssel, Zeile ${index + 1}`}
                  disabled={!editable}
                  checked={rule.primaryKey}
                  onChange={(event) => setField(index, "primaryKey", event.target.checked)}
                />
              </td>
              {textCell(rule, index, "transformation", "Transformation")}
              {textCell(rule, index, "description", "Beschreibung")}
              <td className={cellClass(rule, "requirementKey")} title={cellTitle(rule, "requirementKey")}>
                <Autocomplete
                  label={`Anforderung, Zeile ${index + 1}`}
                  disabled={!editable}
                  value={rule.requirementKey ?? ""}
                  search={async (query) =>
                    (await api.searchRequirements(query)).map((issue) => ({ value: issue.key, hint: issue.summary }))
                  }
                  onChange={(value) => setField(index, "requirementKey", value || null)}
                  onPaste={(event) => handlePaste(event, index, "requirementKey")}
                />
              </td>
              <td>
                {editable && (
                  <button
                    type="button"
                    className="icon"
                    aria-label={`Zeile ${index + 1} löschen`}
                    onClick={() => onChange(rules.filter((_, i) => i !== index))}
                  >
                    ×
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {rules.length === 0 && (
        <p className="empty">Noch keine Regeln. Lege eine Zeile an, importiere eine Excel-Datei oder füge Zellen aus Excel ein.</p>
      )}
    </div>
  );
}
