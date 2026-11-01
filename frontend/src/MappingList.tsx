import { type FormEvent, useEffect, useState } from "react";
import { api, type MappingSummary, type Me } from "./api";
import { STATUS_LABELS, toTechnicalName } from "./labels";

interface Props {
  me: Me;
  release: string | null;
  onOpen: (id: string) => void;
}

export function MappingList({ me, release, onOpen }: Props) {
  const [mappings, setMappings] = useState<MappingSummary[] | null>(null);
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.listMappings(release).then(setMappings).catch((cause: Error) => setError(cause.message));
  }, [release]);

  async function takeOver(key: string) {
    setError(null);
    try {
      const fork = await api.forkMapping(release!, key);
      onOpen(fork.id);
    } catch (cause) {
      setError((cause as Error).message);
    }
  }

  async function create(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      const created = await api.createMapping(name.trim(), toTechnicalName(name), release);
      onOpen(created.id);
    } catch (cause) {
      setError((cause as Error).message);
    }
  }

  return (
    <main className="page">
      <h1>Mappings</h1>
      {error && <p role="alert" className="error">{error}</p>}
      {me.roles.includes("EDITOR") && (
        <form className="toolbar panel" onSubmit={create}>
          <label className="field grow">
            <span>Neues Mapping</span>
            <input value={name} onChange={(event) => setName(event.target.value)} placeholder="z. B. Kundenstamm" />
          </label>
          <button type="submit" className="primary" disabled={!toTechnicalName(name)}>Anlegen</button>
        </form>
      )}
      <section className="panel">
        {mappings === null && !error && <p>Lädt…</p>}
        {mappings?.length === 0 && <p className="empty">Es gibt noch keine Mappings.</p>}
        {mappings && mappings.length > 0 && (
          <table className="list">
            <thead>
              <tr>
                <th>Name</th>
                <th>Contract</th>
                <th>Version</th>
                <th>Status</th>
                <th>Team</th>
                <th>Regeln</th>
                <th>Geändert</th>
                {release && <th>Stand aus</th>}
              </tr>
            </thead>
            <tbody>
              {mappings.map((mapping) => (
                <tr key={mapping.id}>
                  <td>
                    <button type="button" className="link" onClick={() => onOpen(mapping.id)}>{mapping.name}</button>
                  </td>
                  <td><code>{mapping.contractName}</code></td>
                  <td>{mapping.contractVersion}</td>
                  <td><span className={`status status-${mapping.status}`}>{STATUS_LABELS[mapping.status]}</span></td>
                  <td>{mapping.ownerTeam ?? "–"}</td>
                  <td>{mapping.ruleCount}</td>
                  <td>{new Date(mapping.updatedAt).toLocaleString("de-DE")}</td>
                  {release && (
                    <td>
                      {mapping.inherited ? (
                        <span className="inherited">
                          {mapping.originRelease ?? "main"}
                          {me.roles.includes("EDITOR") && mapping.status === "PUBLISHED" && (
                            <button type="button" onClick={() => takeOver(mapping.key)}>Ins Release übernehmen</button>
                          )}
                        </span>
                      ) : (
                        "diesem Release"
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  );
}
