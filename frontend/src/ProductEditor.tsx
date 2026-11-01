import { useEffect, useState } from "react";
import {
  api,
  type ContractOption,
  type Me,
  type PreviewFile,
  type ProductDetail,
  reviewActions,
} from "./api";
import { ConflictPanel, ScopeBadge } from "./ConflictPanel";
import { IntegrationPanel } from "./IntegrationPanel";
import { PRODUCT_TYPES, STATUS_LABELS } from "./labels";
import { PortTable } from "./PortTable";
import { ReviewPanel } from "./ReviewPanel";

interface Props {
  id: string;
  me: Me;
  onBack: () => void;
  onDirtyChange: (dirty: boolean) => void;
}

export function ProductEditor({ id, me, onBack, onDirtyChange }: Props) {
  const [product, setProduct] = useState<ProductDetail | null>(null);
  const [contracts, setContracts] = useState<ContractOption[]>([]);
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<PreviewFile[] | null>(null);

  useEffect(() => {
    api
      .getProduct(id)
      .then((loaded) => {
        setProduct(loaded);
        return api.contracts(loaded.releaseId).then(setContracts);
      })
      .catch((cause: Error) => setError(cause.message));
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

  if (!product) {
    return <main className="page">{error ? <p role="alert" className="error">{error}</p> : <p>Lädt…</p>}</main>;
  }

  const current = product;
  const editable = me.roles.includes("EDITOR") && (current.status === "DRAFT" || current.status === "CHANGES_REQUESTED");

  function edit(changes: Partial<ProductDetail>) {
    setProduct({ ...current, ...changes });
    setDirty(true);
  }

  function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    action()
      .catch((cause: Error) => setError(cause.message))
      .finally(() => setBusy(false));
  }

  function applyServerState(updated: ProductDetail) {
    setProduct(updated);
    setDirty(false);
    setPreview(null);
  }

  const errors = current.issues.filter((issue) => issue.severity === "ERROR");
  const warnings = current.issues.filter((issue) => issue.severity === "WARNING");

  return (
    <main className="page">
      <header className="toolbar">
        <button type="button" onClick={onBack}>← Übersicht</button>
        <h1>{current.name}</h1>
        <span className={`status status-${current.status}`}>{STATUS_LABELS[current.status]}</span>
        <ScopeBadge item={current} />
        <span className="spacer" />
        {dirty && <span className="meta">Nicht gespeichert</span>}
        {editable && (
          <button
            type="button"
            className="primary"
            disabled={busy || !dirty}
            onClick={() => run(async () => applyServerState(await api.updateProduct(current)))}
          >
            Speichern
          </button>
        )}
      </header>

      {error && <p role="alert" className="error">{error}</p>}

      <ConflictPanel
        conflicts={current.conflicts}
        editable={editable}
        dirty={dirty}
        onResolve={(conflictId, takeTheirs) =>
          run(async () =>
            applyServerState(await reviewActions<ProductDetail>(`/api/products/${current.id}`).resolveConflict(conflictId, takeTheirs)),
          )
        }
      />

      <section className="panel" aria-label="Kopfdaten">
        <div className="form-grid">
          <label className="field">
            <span>Name</span>
            <input disabled={!editable} value={current.name} onChange={(event) => edit({ name: event.target.value })} />
          </label>
          <label className="field">
            <span>Produkt</span>
            <input disabled value={current.productName} />
          </label>
          <label className="field">
            <span>Version</span>
            <input
              disabled={!editable}
              value={current.productVersion}
              onChange={(event) => edit({ productVersion: event.target.value })}
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
            <span>Typ</span>
            <select
              disabled={!editable}
              value={current.productType ?? ""}
              onChange={(event) => edit({ productType: event.target.value || null })}
            >
              <option value="">Nicht festgelegt</option>
              {PRODUCT_TYPES.map((type) => (
                <option key={type.value} value={type.value}>{type.label}</option>
              ))}
            </select>
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
              value={current.purpose ?? ""}
              onChange={(event) => edit({ purpose: event.target.value || null })}
            />
          </label>
        </div>
      </section>

      <section className="panel" aria-label="Ports">
        <h2>Ports</h2>
        <PortTable
          direction="INPUT"
          title="Input-Port"
          hint="Welche Contracts liest das Produkt?"
          ports={current.ports}
          contracts={contracts}
          issues={current.issues}
          editable={editable}
          onChange={(ports) => edit({ ports })}
        />
        <PortTable
          direction="OUTPUT"
          title="Output-Port"
          hint="Welche Contracts liefert das Produkt?"
          ports={current.ports}
          contracts={contracts}
          issues={current.issues}
          editable={editable}
          onChange={(ports) => edit({ ports })}
        />
      </section>

      <div className="columns">
        <section className="panel" aria-label="Prüfung">
          <h2>Prüfung</h2>
          {dirty && <p className="meta">Wird beim Speichern aktualisiert.</p>}
          {errors.length === 0 && warnings.length === 0 && <p className="ok">Keine Befunde.</p>}
          <ul className="issues">
            {[...errors, ...warnings].map((issue, index) => (
              <li
                key={`${issue.code}-${issue.ruleId}-${index}`}
                className={issue.severity === "ERROR" ? "issue-error" : "issue-warning"}
              >
                <strong>{issue.severity === "ERROR" ? "Fehler" : "Hinweis"}</strong>: {issue.message}
              </li>
            ))}
          </ul>
          <button
            type="button"
            disabled={busy}
            onClick={() => run(async () => setPreview(await api.previewProduct(current.id)))}
          >
            Vorschau der Git-Datei
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
          actions={reviewActions<ProductDetail>(`/api/products/${current.id}`)}
          me={me}
          dirty={dirty}
          onUpdated={applyServerState}
          run={run}
        />
      </div>

      <IntegrationPanel subjectId={current.key} revision={current.version} me={me} />
    </main>
  );
}
