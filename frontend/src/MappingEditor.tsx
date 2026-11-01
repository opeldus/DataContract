import { useEffect, useRef, useState } from "react";
import {
  ApiError,
  api,
  type ContractConsumer,
  type MappingDetail,
  type Me,
  type PreviewFile,
  reviewActions,
  ruleIndexOf,
} from "./api";
import { STATUS_LABELS } from "./labels";
import { ReviewPanel } from "./ReviewPanel";
import { emptyRule, RuleGrid } from "./RuleGrid";

interface Props {
  id: string;
  me: Me;
  onBack: () => void;
  onOpenProduct: (id: string) => void;
  onDirtyChange: (dirty: boolean) => void;
}

export function MappingEditor({ id, me, onBack, onOpenProduct, onDirtyChange }: Props) {
  const [mapping, setMapping] = useState<MappingDetail | null>(null);
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [selectedRuleId, setSelectedRuleId] = useState<string | null>(null);
  const [preview, setPreview] = useState<PreviewFile[] | null>(null);
  const [consumers, setConsumers] = useState<ContractConsumer[]>([]);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    api.getMapping(id).then(setMapping).catch((cause: Error) => setError(cause.message));
    api.consumers(id).then(setConsumers).catch((cause: Error) => setError(cause.message));
  }, [id]);

  useEffect(() => {
    onDirtyChange(dirty);
  }, [dirty, onDirtyChange]);

  useEffect(() => {
    if (!dirty) {
      return;
    }
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  if (!mapping) {
    return <main className="page">{error ? <p role="alert" className="error">{error}</p> : <p>Lädt…</p>}</main>;
  }

  const current = mapping;
  const editable = me.roles.includes("EDITOR") && (current.status === "DRAFT" || current.status === "CHANGES_REQUESTED");

  function edit(changes: Partial<MappingDetail>) {
    setMapping({ ...current, ...changes });
    setDirty(true);
    setNotice(null);
  }

  function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    setNotice(null);
    action()
      .catch((cause: Error) => {
        const reloadHint = cause instanceof ApiError && cause.status === 409 ? " Nicht gespeicherte Eingaben bleiben sichtbar." : "";
        setError(cause.message + reloadHint);
      })
      .finally(() => setBusy(false));
  }

  function applyServerState(updated: MappingDetail) {
    setMapping(updated);
    setDirty(false);
    setPreview(null);
  }

  const selectedRuleIndex = ruleIndexOf(current, selectedRuleId);
  const errors = current.issues.filter((issue) => issue.severity === "ERROR");
  const warnings = current.issues.filter((issue) => issue.severity === "WARNING");

  return (
    <main className="page">
      <header className="toolbar">
        <button type="button" onClick={onBack}>← Übersicht</button>
        <h1>{current.name}</h1>
        <span className={`status status-${current.status}`}>{STATUS_LABELS[current.status]}</span>
        <span className="spacer" />
        {dirty && <span className="meta">Nicht gespeichert</span>}
        {editable && (
          <button
            type="button"
            className="primary"
            disabled={busy || !dirty}
            onClick={() => run(async () => applyServerState(await api.updateMapping(current)))}
          >
            Speichern
          </button>
        )}
      </header>

      {error && <p role="alert" className="error">{error}</p>}
      {notice && <p role="status" className="notice">{notice}</p>}

      <section className="panel" aria-label="Kopfdaten">
        <div className="form-grid">
          <label className="field">
            <span>Name</span>
            <input disabled={!editable} value={current.name} onChange={(event) => edit({ name: event.target.value })} />
          </label>
          <label className="field">
            <span>Contract</span>
            <input disabled value={current.contractName} />
          </label>
          <label className="field">
            <span>Version</span>
            <input
              disabled={!editable}
              value={current.contractVersion}
              onChange={(event) => edit({ contractVersion: event.target.value })}
            />
          </label>
          <label className="field">
            <span>Domäne</span>
            <input
              disabled={!editable}
              value={current.domain ?? ""}
              onChange={(event) => edit({ domain: event.target.value || null })}
            />
          </label>
          <label className="field">
            <span>Zieltabelle</span>
            <input
              disabled={!editable}
              value={current.targetTable ?? ""}
              onChange={(event) => edit({ targetTable: event.target.value || null })}
            />
          </label>
          <label className="field">
            <span>Team</span>
            <input
              disabled={!editable}
              value={current.ownerTeam ?? ""}
              onChange={(event) => edit({ ownerTeam: event.target.value || null })}
            />
          </label>
          <label className="field wide">
            <span>Zweck</span>
            <input
              disabled={!editable}
              value={current.description ?? ""}
              onChange={(event) => edit({ description: event.target.value || null })}
            />
          </label>
        </div>
      </section>

      <section className="panel" aria-label="Regeln">
        <div className="toolbar">
          <h2>Regeln</h2>
          <span className="spacer" />
          {editable && (
            <>
              <button type="button" onClick={() => edit({ rules: [...current.rules, emptyRule()] })}>
                Zeile hinzufügen
              </button>
              <button
                type="button"
                disabled={busy || dirty}
                title={dirty ? "Bitte zuerst speichern" : undefined}
                onClick={() => fileInput.current?.click()}
              >
                Excel importieren
              </button>
              <input
                ref={fileInput}
                type="file"
                hidden
                accept=".xlsx,.xls"
                aria-label="Excel-Datei"
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  event.target.value = "";
                  if (file) {
                    run(async () => {
                      const result = await api.importExcel(current.id, file);
                      applyServerState(result.mapping);
                      const ignored = result.ignoredColumns.length
                        ? ` Nicht übernommene Spalten: ${result.ignoredColumns.join(", ")}.`
                        : "";
                      const skipped = result.skippedRows ? ` ${result.skippedRows} Zeilen ohne Zielfeld übersprungen.` : "";
                      setNotice(`${result.imported} Regeln importiert.${skipped}${ignored}`);
                    });
                  }
                }}
              />
            </>
          )}
        </div>
        <RuleGrid
          rules={current.rules}
          issues={current.issues}
          editable={editable}
          selectedRuleId={selectedRuleId}
          onChange={(rules) => edit({ rules })}
          onSelectRule={setSelectedRuleId}
        />
      </section>

      <div className="columns">
        <section className="panel" aria-label="Prüfung">
          <h2>Prüfung</h2>
          {dirty && <p className="meta">Wird beim Speichern aktualisiert.</p>}
          {errors.length === 0 && warnings.length === 0 && <p className="ok">Keine Befunde.</p>}
          <ul className="issues">
            {[...errors, ...warnings].map((issue, index) => {
              const ruleIndex = ruleIndexOf(current, issue.ruleId);
              return (
                <li key={`${issue.code}-${issue.ruleId}-${index}`} className={issue.severity === "ERROR" ? "issue-error" : "issue-warning"}>
                  <strong>{issue.severity === "ERROR" ? "Fehler" : "Hinweis"}</strong>
                  {ruleIndex >= 0 && ` · Regel ${ruleIndex + 1}`}: {issue.message}
                </li>
              );
            })}
          </ul>
          <button
            type="button"
            disabled={busy}
            onClick={() => run(async () => setPreview(await api.preview(current.id)))}
          >
            Vorschau der Git-Dateien
          </button>
          {preview?.map((file) => (
            <details key={file.path} open>
              <summary><code>{file.path}</code></summary>
              <pre>{file.content}</pre>
            </details>
          ))}
        </section>

        <ReviewPanel
          item={current}
          actions={reviewActions<MappingDetail>(`/api/mappings/${current.id}`)}
          me={me}
          dirty={dirty}
          target={selectedRuleIndex >= 0 ? { id: selectedRuleId!, label: `Regel ${selectedRuleIndex + 1}` } : null}
          targetLabel={(ruleId) => {
            const index = ruleIndexOf(current, ruleId);
            return index >= 0 ? `Regel ${index + 1}` : null;
          }}
          onUpdated={applyServerState}
          run={run}
        />
      </div>

      <section className="panel" aria-label="Verwendet von">
        <h2>Verwendet von</h2>
        {consumers.length === 0 && <p className="empty">Noch kein Datenprodukt nutzt diesen Contract.</p>}
        {consumers.length > 0 && (
          <ul className="consumers">
            {consumers.map((consumer) => (
              <li key={`${consumer.productId}-${consumer.direction}-${consumer.portName}`}>
                <button type="button" className="link" onClick={() => onOpenProduct(consumer.productId)}>
                  {consumer.productTitle}
                </button>{" "}
                {consumer.direction === "INPUT" ? "liest" : "liefert"} Version {consumer.contractVersion ?? "–"}
                {consumer.portName && <> über Port <code>{consumer.portName}</code></>}
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}
