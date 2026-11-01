import { useEffect, useMemo, useState } from "react";
import { api, type IntegrationStatus, type Trace, type TraceEntry } from "./api";
import { STATUS_LABELS } from "./labels";

const CHECK_LABELS: Record<TraceEntry["check"], string> = {
  FOUND: "",
  NOT_FOUND: "In Jira nicht gefunden oder nicht lesbar",
  UNKNOWN: "Noch nicht geprüft",
  INVALID_FORMAT: "Kein gültiger Jira-Schlüssel",
};

interface Props {
  onOpenMapping: (id: string) => void;
  onOpenProduct: (id: string) => void;
}

export function RequirementTrace({ onOpenMapping, onOpenProduct }: Props) {
  const [trace, setTrace] = useState<Trace | null>(null);
  const [status, setStatus] = useState<IntegrationStatus | null>(null);
  const [filter, setFilter] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.trace().then(setTrace).catch((cause: Error) => setError(cause.message));
    api.integrationStatus().then(setStatus).catch((cause: Error) => setError(cause.message));
  }, []);

  const entries = useMemo(() => {
    const query = filter.trim().toLowerCase();
    return (trace?.requirements ?? []).filter(
      (entry) =>
        !query ||
        entry.key.toLowerCase().includes(query) ||
        (entry.summary ?? "").toLowerCase().includes(query) ||
        entry.rules.some((rule) => rule.contractName.includes(query) || (rule.targetColumn ?? "").toLowerCase().includes(query)),
    );
  }, [trace, filter]);

  if (error) {
    return <main className="page"><p role="alert" className="error">{error}</p></main>;
  }
  if (!trace) {
    return <main className="page"><p>Lädt…</p></main>;
  }

  return (
    <main className="page">
      <h1>Anforderungen</h1>
      {status && !status.jira && (
        <p className="meta">Jira ist nicht angebunden. Angezeigt werden nur die eingetragenen Schlüssel.</p>
      )}
      {status?.jira && !status.publicUrl && (
        <p className="error">Ohne APP_PUBLIC_URL entstehen in Jira keine Links zu den Contracts.</p>
      )}
      <section className="panel">
        <label className="field trace-filter">
          <span>Suchen nach Schlüssel, Titel, Contract oder Feld</span>
          <input value={filter} onChange={(event) => setFilter(event.target.value)} />
        </label>
        {trace.rulesWithoutRequirement > 0 && (
          <p className="meta">{trace.rulesWithoutRequirement} Regeln haben keine Anforderung.</p>
        )}
        {trace.requirements.length === 0 && <p className="empty">Noch keine Regel verweist auf eine Anforderung.</p>}
        {trace.requirements.length > 0 && entries.length === 0 && <p className="empty">Keine Treffer.</p>}
        {entries.length > 0 && (
          <div className="change-table-wrapper">
            <table className="trace-table">
              <thead>
                <tr>
                  <th>Anforderung</th>
                  <th>Status in Jira</th>
                  <th>Regeln</th>
                  <th>Datenprodukte</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((entry) => (
                  <tr key={entry.key}>
                    <td>
                      {entry.url && entry.check !== "NOT_FOUND" ? (
                        <a href={entry.url} target="_blank" rel="noreferrer"><code>{entry.key}</code></a>
                      ) : (
                        <code>{entry.key}</code>
                      )}
                      {entry.summary && <div>{entry.summary}</div>}
                      {status?.jira && CHECK_LABELS[entry.check] && (
                        <div className={entry.check === "UNKNOWN" ? "meta" : "error"}>{CHECK_LABELS[entry.check]}</div>
                      )}
                    </td>
                    <td>{entry.status ?? "–"}</td>
                    <td>
                      <ul>
                        {entry.rules.map((rule, index) => (
                          <li key={`${rule.mappingId}-${rule.targetColumn}-${index}`}>
                            <button type="button" className="link" onClick={() => onOpenMapping(rule.mappingId)}>
                              {rule.contractName}
                            </button>{" "}
                            <code>{rule.targetColumn ?? "–"}</code>{" "}
                            <span className="meta">{rule.contractVersion}, {STATUS_LABELS[rule.status]}</span>
                          </li>
                        ))}
                      </ul>
                    </td>
                    <td>
                      {entry.products.length === 0 && "–"}
                      <ul>
                        {entry.products.map((product) => (
                          <li key={product.productId}>
                            <button type="button" className="link" onClick={() => onOpenProduct(product.productId)}>
                              {product.productTitle}
                            </button>
                          </li>
                        ))}
                      </ul>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </main>
  );
}
