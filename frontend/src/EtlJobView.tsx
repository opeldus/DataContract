import { useEffect, useMemo, useState } from "react";
import { etlApi, type EtlJobDetail, type Me } from "./api";
import { JOB_KIND_LABELS } from "./EtlOverview";
import { FlowGraph, type FlowNode } from "./FlowGraph";
import { SequenceDiagram } from "./SequenceDiagram";

interface Props {
  id: string;
  me: Me;
  onBack: () => void;
  onOpenJob: (name: string) => void;
}

export function EtlJobView({ id, me, onBack, onOpenJob }: Props) {
  const [detail, setDetail] = useState<EtlJobDetail | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setDetail(null);
    setSelectedId(null);
    etlApi.job(id).then(setDetail).catch((cause: Error) => setError(cause.message));
  }, [id]);

  const nodes = useMemo<FlowNode[]>(
    () =>
      (detail?.job.stages ?? []).map((stage) => ({
        id: stage.id,
        title: stage.name,
        detail: stage.stageType ?? stage.oleType ?? "Stage",
        variant: "contract",
      })),
    [detail],
  );

  if (error) {
    return <main className="page"><p role="alert" className="error">{error}</p></main>;
  }
  if (!detail) {
    return <main className="page"><p>Lädt…</p></main>;
  }

  const { job } = detail;
  const stage = job.stages.find((candidate) => candidate.id === selectedId) ?? null;

  async function remove() {
    if (window.confirm(`Die Analyse von ${job.name} löschen? In DataStage ändert sich dadurch nichts.`)) {
      try {
        await etlApi.remove(id);
        onBack();
      } catch (cause) {
        setError((cause as Error).message);
      }
    }
  }

  return (
    <main className="page">
      <div className="toolbar">
        <button type="button" onClick={onBack}>← Übersicht</button>
        <h1>{job.name}</h1>
        <span className="status">{JOB_KIND_LABELS[job.kind]}</span>
        <span className="spacer" />
        {me.roles.includes("EDITOR") && <button type="button" onClick={remove}>Analyse löschen</button>}
      </div>
      <section className="panel">
        {job.description && <p>{job.description}</p>}
        <p className="meta">
          Ordner {job.category ?? "–"}, aus {detail.sourceFile}, importiert von {detail.importedBy} am{" "}
          {new Date(detail.importedAt).toLocaleString("de-DE")}
        </p>
        {job.warnings.length > 0 && (
          <ul className="issues">
            {job.warnings.map((warning) => (
              <li key={warning} className="issue-warning"><strong>Hinweis</strong>: {warning}</li>
            ))}
          </ul>
        )}
      </section>
      <section className="panel" aria-label="Ablauf">
        <h2>Ablauf</h2>
        <p className="meta">Wähle eine Stage, um Tabellen und SQL zu sehen.</p>
        <FlowGraph
          label={`Stages von ${job.name}`}
          nodes={nodes}
          edges={job.links}
          selectedId={selectedId}
          onSelect={setSelectedId}
        />
      </section>
      {stage && (
        <section className="panel" aria-label="Stage">
          <h2>{stage.name}</h2>
          <p className="meta">{stage.stageType ?? stage.oleType ?? "Stage"}</p>
          {stage.calledJob && (
            <p>
              Startet{" "}
              <button type="button" className="link" onClick={() => onOpenJob(stage.calledJob!)}>{stage.calledJob}</button>
            </p>
          )}
          {stage.tables.length === 0 && !stage.calledJob && <p className="empty">Keine Tabellen oder Dateien erkannt.</p>}
          {stage.tables.length > 0 && (
            <ul>
              {stage.tables.map((table) => (
                <li key={`${table.access}-${table.name}`}>
                  {table.access === "READ" ? "Liest" : "Schreibt"} <code>{table.name}</code> ({table.operation})
                </li>
              ))}
            </ul>
          )}
          {stage.sql.map((statement) => <pre key={statement}>{statement}</pre>)}
        </section>
      )}
      <section className="panel" aria-label="Sequenzdiagramm">
        <h2>Sequenzdiagramm</h2>
        <SequenceDiagram text={detail.sequence} fileName={job.name} />
      </section>
    </main>
  );
}
