import { useCallback } from "react";
import type { ContractOption, Port, PortDirection, ValidationIssue } from "./api";
import { Autocomplete } from "./Autocomplete";

interface Props {
  direction: PortDirection;
  title: string;
  hint: string;
  ports: Port[];
  contracts: ContractOption[];
  issues: ValidationIssue[];
  editable: boolean;
  onChange: (ports: Port[]) => void;
}

export function PortTable({ direction, title, hint, ports, contracts, issues, editable, onChange }: Props) {
  const rows = ports.map((port, index) => ({ port, index })).filter(({ port }) => port.direction === direction);
  const searchContracts = useCallback(
    async (query: string) =>
      contracts
        .filter((contract) => contract.name.includes(query.toLowerCase()))
        .slice(0, 20)
        .map((contract) => ({
          value: contract.name,
          hint: contract.publishedVersions[0] ?? "nicht veröffentlicht",
        })),
    [contracts],
  );

  function update(index: number, changes: Partial<Port>) {
    onChange(ports.map((port, i) => (i === index ? { ...port, ...changes } : port)));
  }

  function chooseContract(index: number, name: string) {
    const contract = contracts.find((option) => option.name === name);
    update(index, {
      contractName: name || null,
      contractMappingId: contract?.mappingId ?? null,
      contractVersion: contract?.publishedVersions[0] ?? null,
    });
  }

  function cellClass(port: Port, field: string) {
    const cellIssues = issues.filter((issue) => issue.ruleId !== null && issue.ruleId === port.id && issue.field === field);
    if (cellIssues.some((issue) => issue.severity === "ERROR")) {
      return "cell-error";
    }
    return cellIssues.length > 0 ? "cell-warning" : undefined;
  }

  return (
    <div className="port-section">
      <div className="toolbar">
        <h3>{title}</h3>
        <span className="meta">{hint}</span>
        <span className="spacer" />
        {editable && (
          <button
            type="button"
            onClick={() =>
              onChange([
                ...ports,
                { id: null, direction, name: null, contractMappingId: null, contractName: null, contractVersion: null },
              ])
            }
          >
            {title} hinzufügen
          </button>
        )}
      </div>
      {rows.length === 0 && <p className="empty">Keine Einträge.</p>}
      {rows.length > 0 && (
        <div className="port-wrapper">
          <table className="port-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Contract</th>
                <th className="col-version">Version</th>
                <th className="col-action" aria-label="Aktionen" />
              </tr>
            </thead>
            <tbody>
              {rows.map(({ port, index }, rowIndex) => {
                const versions =
                  contracts.find((contract) => contract.mappingId === port.contractMappingId)?.publishedVersions ?? [];
                const label = `${title} ${rowIndex + 1}`;
                return (
                  <tr key={port.id ?? `new-${index}`}>
                    <td className={cellClass(port, "name")}>
                      <input
                        aria-label={`Name, ${label}`}
                        disabled={!editable}
                        value={port.name ?? ""}
                        onChange={(event) => update(index, { name: event.target.value || null })}
                      />
                    </td>
                    <td className={cellClass(port, "contract")}>
                      <Autocomplete
                        label={`Contract, ${label}`}
                        disabled={!editable}
                        value={port.contractName ?? ""}
                        search={searchContracts}
                        onChange={(value) => chooseContract(index, value)}
                      />
                    </td>
                    <td className={cellClass(port, "contractVersion")}>
                      <select
                        aria-label={`Version, ${label}`}
                        disabled={!editable || versions.length === 0}
                        value={port.contractVersion ?? ""}
                        onChange={(event) => update(index, { contractVersion: event.target.value || null })}
                      >
                        {versions.length === 0 && <option value="">–</option>}
                        {port.contractVersion && !versions.includes(port.contractVersion) && (
                          <option value={port.contractVersion}>{port.contractVersion}</option>
                        )}
                        {versions.map((version) => (
                          <option key={version} value={version}>{version}</option>
                        ))}
                      </select>
                    </td>
                    <td>
                      {editable && (
                        <button
                          type="button"
                          className="icon"
                          aria-label={`${label} löschen`}
                          onClick={() => onChange(ports.filter((_, i) => i !== index))}
                        >
                          ×
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
