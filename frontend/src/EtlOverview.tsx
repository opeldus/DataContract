import { type ChangeEvent, useCallback, useEffect, useMemo, useState } from "react";
import { etlApi, type EtlGraph, type EtlImportReport, type EtlJobSummary, type EtlNode, type Me } from "./api";
import { FlowGraph, type FlowNode } from "./FlowGraph";
import { downstreamOf } from "./graphLayout";
import { SequenceDiagram } from "./SequenceDiagram";

const KIND_LABELS: Record<EtlNode["kind"], string> = {
  JOB: "Job",
  SEQUENCE: "Sequenz",
  MISSING_JOB: "Job, nicht importiert",
  TABLE: "Tabelle",
  FILE: "Datei",
};

export const JOB_KIND_LABELS = { PARALLEL: "Job", SEQUENCE: "Sequenz", UNKNOWN: "Unbekannt" };

function variantOf(node: EtlNode): FlowNode["variant"] {
  if (node.kind === "TABLE" || node.kind === "FILE") {
    return "asset";
  }
  return node.kind === "SEQUENCE" ? "product" : "contract";
}

interface Props {
  me: Me;
  onOpen: (id: string) => void;
}

export function EtlOverview({ me, onOpen }: Props) {
  const [jobs, setJobs] = useState<EtlJobSummary[] | null>(null);
  const [graph, setGraph] = useState<EtlGraph | null>(null);
  const [report, setReport] = useState<EtlImportReport | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [sequence, setSequence] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    etlApi.jobs().then(setJobs).catch((cause: Error) => setError(cause.message));
    etlApi.graph().then(setGraph).catch((cause: Error) => setError(cause.message));
  }, []);

  useEffect(load, [load]);

  useEffect(() => {
    setSequence(null);
    if (selectedId) {
      etlApi.sequence(selectedId).then((result) => setSequence(result.mermaid)).catch((cause: Error) => setError(cause.message));
    }
  }, [selectedId]);

  async function upload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) {
      return;
    }
    setError(null);
    setReport(null);
    setBusy(true);
    try {
      setReport(await etlApi.importFile(file));
      setSelectedId(null);
      load();
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const nodes = useMemo<FlowNode[]>(
    () => (graph?.nodes ?? []).map((node) => ({ id: node.id, title: node.label, detail: KIND_LABELS[node.kind], variant: variantOf(node) })),
    [graph],
  );
  const chain = useMemo(() => {
    if (!selectedId || !graph) {
      return undefined;
    }
    const reversed = graph.edges.map((edge) => ({ from: edge.to, to: edge.from }));
    return new Set([selectedId, ...downstreamOf(selectedId, graph.edges), ...downstreamOf(selectedId, reversed)]);
  }, [selectedId, graph]);
  const selected = graph?.nodes.find((node) => node.id === selectedId) ?? null;

  return (
    <main className="page">
      <h1>DataStage</h1>
      {error && <p role="alert" className="error">{error}</p>}
      {me.roles.includes("EDITOR") && (
        <section className="panel">
          <label className="field">
            <span>DSX-Datei oder ISX-Paket importieren. Vorhandene Jobs mit gleichem Namen werden ersetzt.</span>
            <input type="file" accept=".dsx,.isx,.zip" disabled={busy} onChange={upload} />
          </label>
          {busy && <p>Wird analysiert…</p>}
          {report && (
            <div className="etl-report" role="status">
              <p className={report.imported.length > 0 ? "notice" : "error"}>{report.imported.length} Jobs importiert.</p>
              {report.unreadable.length > 0 && (
                <>
                  <p className="error">
                    {report.unreadable.length} Dateien konnten nicht gelesen werden. Das innere XML von ISX-Paketen ist nicht dokumentiert.
                    Exportiere diese Jobs als DSX, oder nutze die Liste, um das Format zu ergänzen.
                  </p>
                  <details>
                    <summary>Nicht gelesene Dateien</summary>
                    <ul>
                      {report.unreadable.slice(0, 200).map((entry) => (
                        <li key={entry.path}><code>{entry.path}</code>: {entry.content}</li>
                      ))}
                    </ul>
                  </details>
                </>
              )}
              {report.warnings.length > 0 && <ul>{report.warnings.map((warning) => <li key={warning}>{warning}</li>)}</ul>}
            </div>
          )}
        </section>
      )}
      <section className="panel" aria-label="Jobs">
        <h2>Jobs</h2>
        {jobs === null && !error && <p>Lädt…</p>}
        {jobs?.length === 0 && <p className="empty">Es sind noch keine Jobs importiert.</p>}
        {jobs && jobs.length > 0 && (
          <table className="list">
            <thead>
              <tr>
                <th>Name</th>
                <th>Art</th>
                <th>Ordner</th>
                <th>Stages</th>
                <th>Hinweise</th>
                <th>Quelle</th>
                <th>Importiert</th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((job) => (
                <tr key={job.id}>
                  <td><button type="button" className="link" onClick={() => onOpen(job.id)}>{job.name}</button></td>
                  <td>{JOB_KIND_LABELS[job.kind]}</td>
                  <td>{job.category ?? "–"}</td>
                  <td>{job.stages}</td>
                  <td>{job.warnings}</td>
                  <td>{job.sourceFile}</td>
                  <td>{new Date(job.importedAt).toLocaleString("de-DE")}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
      {graph && graph.nodes.length > 0 && (
        <section className="panel" aria-label="Verarbeitungskette">
          <h2>Verarbeitungskette</h2>
          <p className="meta">Daten fließen von links nach rechts. Wähle eine Tabelle oder einen Job, um die ganze Kette von der Quelle bis zum Ziel zu sehen.</p>
          <FlowGraph
            label="Jobs, Tabellen und Dateien mit ihren Verbindungen"
            nodes={nodes}
            edges={graph.edges}
            selectedId={selectedId}
            highlighted={chain}
            onSelect={setSelectedId}
          />
        </section>
      )}
      {selected && (
        <section className="panel" aria-label="Kette als Sequenzdiagramm">
          <div className="toolbar">
            <h2>Kette von {selected.label}</h2>
            <span className="spacer" />
            {selected.jobId && <button type="button" onClick={() => onOpen(selected.jobId!)}>Job öffnen</button>}
          </div>
          {sequence ? <SequenceDiagram text={sequence} fileName={`kette-${selected.label}`} /> : <p>Lädt…</p>}
        </section>
      )}
    </main>
  );
}
