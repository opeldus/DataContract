import { type FormEvent, useState } from "react";
import { api, type Me, type Release, type ReleaseItem } from "./api";
import { STATUS_LABELS, toTechnicalName } from "./labels";

interface Props {
  me: Me;
  releases: Release[];
  selected: string | null;
  onChanged: () => void;
  onSelect: (id: string | null) => void;
  onOpenItem: (kind: ReleaseItem["kind"], id: string) => void;
}

export function ReleaseList({ me, releases, selected, onChanged, onSelect, onOpenItem }: Props) {
  const [title, setTitle] = useState("");
  const [baseId, setBaseId] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const open = releases.filter((release) => release.state === "OPEN");
  const closed = releases.filter((release) => release.state === "RELEASED");

  function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    action()
      .then(onChanged)
      .catch((cause: Error) => setError(cause.message))
      .finally(() => setBusy(false));
  }

  function create(event: FormEvent) {
    event.preventDefault();
    run(async () => {
      const created = await api.createRelease(toTechnicalName(title).slice(0, 50), title.trim(), baseId || null);
      setTitle("");
      setBaseId("");
      onSelect(created.id);
    });
  }

  return (
    <main className="page">
      <h1>Releases</h1>
      {error && <p role="alert" className="error">{error}</p>}
      {me.roles.includes("EDITOR") && (
        <form className="toolbar panel" onSubmit={create}>
          <label className="field grow">
            <span>Neues Release</span>
            <input value={title} onChange={(event) => setTitle(event.target.value)} placeholder="z. B. Release 2027-01" />
          </label>
          <label className="field">
            <span>Baut auf</span>
            <select value={baseId} onChange={(event) => setBaseId(event.target.value)}>
              <option value="">main</option>
              {open.map((release) => (
                <option key={release.id} value={release.id}>{release.name}</option>
              ))}
            </select>
          </label>
          <button type="submit" className="primary" disabled={busy || !toTechnicalName(title)}>Anlegen</button>
        </form>
      )}

      {open.length === 0 && <section className="panel"><p className="empty">Es gibt keine offenen Releases.</p></section>}
      {open.map((release) => (
        <section key={release.id} className="panel" aria-label={`Release ${release.name}`}>
          <div className="toolbar">
            <h2>{release.title}</h2>
            <code>{release.branch}</code>
            <span className="scope">baut auf {release.baseName ?? "main"}</span>
            <span className="spacer" />
            {selected === release.id ? (
              <span className="meta">Ausgewählt</span>
            ) : (
              <button type="button" onClick={() => onSelect(release.id)}>Auswählen</button>
            )}
            {me.roles.includes("REVIEWER") && (
              <button
                type="button"
                className="primary"
                disabled={busy || release.closeBlockers.length > 0}
                onClick={() => {
                  if (window.confirm(`Release ${release.name} nach main übernehmen und abschließen?`)) {
                    run(async () => {
                      await api.closeRelease(release.id);
                    });
                  }
                }}
              >
                Abschließen
              </button>
            )}
          </div>
          {release.closeBlockers.length > 0 && (
            <ul className="consumers meta">
              {release.closeBlockers.map((blocker) => <li key={blocker}>{blocker}</li>)}
            </ul>
          )}
          {release.items.length === 0 && <p className="empty">Dieses Release ändert noch nichts.</p>}
          {release.items.length > 0 && (
            <table className="list">
              <thead>
                <tr>
                  <th>Art</th>
                  <th>Name</th>
                  <th>Version</th>
                  <th>Status</th>
                  <th>Konflikte</th>
                </tr>
              </thead>
              <tbody>
                {release.items.map((item) => (
                  <tr key={item.id}>
                    <td>{item.kind === "PRODUCT" ? "Datenprodukt" : "Contract"}</td>
                    <td>
                      <button type="button" className="link" onClick={() => onOpenItem(item.kind, item.id)}>{item.name}</button>
                    </td>
                    <td>{item.version}</td>
                    <td><span className={`status status-${item.status}`}>{STATUS_LABELS[item.status]}</span></td>
                    <td>{item.openConflicts || "–"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      ))}

      {closed.length > 0 && (
        <section className="panel" aria-label="Abgeschlossene Releases">
          <h2>Abgeschlossen</h2>
          <ul className="consumers">
            {closed.map((release) => (
              <li key={release.id}>
                {release.title} <code>{release.name}</code>
                {release.releasedCommit && <> · Commit <code>{release.releasedCommit.slice(0, 10)}</code></>}
                {release.releasedAt && <> · {new Date(release.releasedAt).toLocaleString("de-DE")}</>}
              </li>
            ))}
          </ul>
        </section>
      )}
    </main>
  );
}
