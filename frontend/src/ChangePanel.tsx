import { useEffect, useState } from "react";
import { api, type ChangeImpact, type ChangeReport, type DdlStatus } from "./api";
import { IMPACT_LABELS } from "./labels";

/** The version part a change requires, matching the rules the backend enforces. */
const REQUIRED_BUMP: Record<ChangeImpact, string> = {
  BREAKING: "Hauptversion nötig",
  CONTENT: "mittlere Versionsstelle empfohlen",
  DOCUMENTATION: "letzte Versionsstelle genügt",
};

const IMPACT_ORDER: ChangeImpact[] = ["BREAKING", "CONTENT", "DOCUMENTATION"];

interface Props {
  mappingId: string;
  /** Reloads the report whenever the saved mapping changes. */
  revision: number;
  dirty: boolean;
  onOpenProduct: (id: string) => void;
  onSelectRule: (ruleId: string | null) => void;
}

export function ChangePanel({ mappingId, revision, dirty, onOpenProduct, onSelectRule }: Props) {
  const [report, setReport] = useState<ChangeReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.changes(mappingId).then(setReport).catch((cause: Error) => setError(cause.message));
  }, [mappingId, revision]);

  if (error) {
    return <section className="panel"><p role="alert" className="error">{error}</p></section>;
  }
  if (!report || report.baselineVersion === null) {
    return null;
  }

  const highest = IMPACT_ORDER.find((impact) => report.changes.some((change) => change.impact === impact));

  return (
    <section className="panel" aria-label="Änderungen">
      <h2>Änderungen seit Version {report.baselineVersion}</h2>
      {dirty && <p className="meta">Wird beim Speichern aktualisiert.</p>}
      {!report.baselineAvailable && (
        <p className="meta">Für diese Version gibt es keinen gespeicherten Vergleichsstand.</p>
      )}
      {report.baselineAvailable && report.changes.length === 0 && <p className="empty">Keine Änderungen.</p>}
      {highest && (
        <div className="change-summary">
          <span className={`impact impact-${highest}`}>{IMPACT_LABELS[highest]}</span>
          <span>{REQUIRED_BUMP[highest]}</span>
          <span className="meta">
            {report.changes.length} {report.changes.length === 1 ? "Änderung" : "Änderungen"}
          </span>
        </div>
      )}
      {report.changes.length > 0 && (
        <div className="change-table-wrapper">
          <table className="change-table">
            <thead>
              <tr>
                <th>Einstufung</th>
                <th>Feld</th>
                <th>Änderung</th>
                <th>Vorher</th>
                <th>Nachher</th>
              </tr>
            </thead>
            <tbody>
              {report.changes.map((change, index) => (
                <tr key={`${change.code}-${change.ruleId ?? change.column ?? ""}-${index}`} onClick={() => onSelectRule(change.ruleId)}>
                  <td><span className={`impact impact-${change.impact}`}>{IMPACT_LABELS[change.impact]}</span></td>
                  <td>{change.column ? <code>{change.column}</code> : "–"}</td>
                  <td>{change.message}</td>
                  <td className="value">{change.before ? <del>{change.before}</del> : "–"}</td>
                  <td className="value">{change.after ? <ins>{change.after}</ins> : "–"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {report.changes.length > 0 && report.affected.length > 0 && (
        <>
          <h3>Betroffene Datenprodukte</h3>
          <ul className="consumers">
            {report.affected.map((product) => (
              <li key={product.productId}>
                <button type="button" className="link" onClick={() => onOpenProduct(product.productId)}>
                  {product.productTitle}
                </button>{" "}
                {product.direct ? `liest Version ${product.pinnedVersion ?? "–"}` : "hängt indirekt davon ab"}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

/** Says which DDL state was compared, so a reviewer can tell how far the check can be trusted. */
export function DdlStatusLine() {
  const [status, setStatus] = useState<DdlStatus | null>(null);

  useEffect(() => {
    api.ddlStatus().then(setStatus).catch(() => setStatus(null));
  }, []);

  if (!status) {
    return null;
  }
  if (!status.configured) {
    return <p className="meta">Kein DDL-Repo angebunden, der DDL-Vergleich entfällt.</p>;
  }
  return (
    <>
      {status.error && <p className="error">Das DDL-Repo konnte nicht gelesen werden: {status.error}</p>}
      {status.revision && (
        <p className="meta">
          DDL-Stand <code>{status.revision.slice(0, 10)}</code> mit {status.tableCount} {status.tableCount === 1 ? "Tabelle" : "Tabellen"}.
        </p>
      )}
      {status.warnings.length > 0 && (
        <details>
          <summary>{status.warnings.length} DDL-Anweisungen nicht verstanden</summary>
          <ul className="consumers">
            {status.warnings.map((warning) => <li key={warning}>{warning}</li>)}
          </ul>
        </details>
      )}
    </>
  );
}
