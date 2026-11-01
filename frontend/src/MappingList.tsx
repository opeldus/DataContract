import { type FormEvent, useEffect, useState } from "react";
import { api, type MappingSummary, type Me } from "./api";
import { STATUS_LABELS } from "./labels";

function toContractName(name: string) {
  return name
    .toLowerCase()
    .replace(/ä/g, "ae")
    .replace(/ö/g, "oe")
    .replace(/ü/g, "ue")
    .replace(/ß/g, "ss")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

interface Props {
  me: Me;
  onOpen: (id: string) => void;
}

export function MappingList({ me, onOpen }: Props) {
  const [mappings, setMappings] = useState<MappingSummary[] | null>(null);
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.listMappings().then(setMappings).catch((cause: Error) => setError(cause.message));
  }, []);

  async function create(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      const created = await api.createMapping(name.trim(), toContractName(name));
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
          <button type="submit" className="primary" disabled={!toContractName(name)}>Anlegen</button>
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
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </main>
  );
}
