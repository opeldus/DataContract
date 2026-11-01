import type { MergeConflict, Reviewable } from "./api";

export function ScopeBadge({ item }: { item: Reviewable }) {
  if (item.archived) {
    return <span className="scope scope-archived">Durch ein Release ersetzt</span>;
  }
  return <span className="scope">{item.releaseName ? `Release ${item.releaseName}` : "main"}</span>;
}

function describeTheirs(conflict: MergeConflict) {
  if (conflict.removedUpstream) {
    return "Im Basisrelease entfernt";
  }
  if (conflict.removedHere) {
    return "Im Basisrelease geändert, hier entfernt";
  }
  return conflict.theirs ?? "leer";
}

interface Props {
  conflicts: MergeConflict[];
  editable: boolean;
  dirty: boolean;
  onResolve: (conflictId: string, takeTheirs: boolean) => void;
}

export function ConflictPanel({ conflicts, editable, dirty, onResolve }: Props) {
  if (conflicts.length === 0) {
    return null;
  }
  return (
    <section className="panel conflict-panel" aria-label="Konflikte">
      <h2>Konflikte mit dem Basisrelease</h2>
      <p className="meta">Das Basisrelease und dieses Release haben dieselbe Stelle unterschiedlich geändert. Bis zur Klärung bleibt der eigene Stand erhalten und das Einreichen gesperrt.</p>
      {dirty && <p className="meta">Bitte zuerst speichern.</p>}
      <div className="change-table-wrapper">
        <table className="change-table">
          <thead>
            <tr>
              <th>Stelle</th>
              <th>Dieses Release</th>
              <th>Basisrelease</th>
              <th aria-label="Aktionen" />
            </tr>
          </thead>
          <tbody>
            {conflicts.map((conflict) => (
              <tr key={conflict.id}>
                <td>
                  <code>{conflict.label}</code>
                  <div className="meta">aus Version {conflict.upstreamVersion}</div>
                </td>
                <td className="value">{conflict.removedHere ? "entfernt" : (conflict.ours ?? (conflict.removedUpstream ? "geändert" : "leer"))}</td>
                <td className="value">{describeTheirs(conflict)}</td>
                <td>
                  {editable && (
                    <div className="actions">
                      <button type="button" disabled={dirty} onClick={() => onResolve(conflict.id, false)}>
                        Eigenen Stand behalten
                      </button>
                      <button type="button" disabled={dirty} onClick={() => onResolve(conflict.id, true)}>
                        Basisrelease übernehmen
                      </button>
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
