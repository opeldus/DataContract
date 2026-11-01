import { useCallback, useEffect, useState } from "react";
import { api, type IntegrationTask, type Me, type SubjectTasks } from "./api";

const POLL_INTERVAL_MS = 5000;

const KIND_LABELS: Record<IntegrationTask["kind"], string> = {
  ISSUE_LINK: "Link in Jira",
  ISSUE_UNLINK: "Link in Jira entfernen",
  DOCUMENTATION_PAGE: "Seite in Confluence",
};

const STATE_LABELS: Record<IntegrationTask["state"], string> = {
  PENDING: "Wartet",
  DONE: "Übertragen",
  FAILED: "Fehlgeschlagen",
};

interface Props {
  subjectId: string;
  /** Reloads the tasks whenever the saved item changes, e.g. after publishing. */
  revision: number;
  me: Me;
}

/** Stays hidden until something was sent, so setups without Atlassian see no empty section. */
export function IntegrationPanel({ subjectId, revision, me }: Props) {
  const [data, setData] = useState<SubjectTasks | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.integrationTasks(subjectId).then(setData).catch((cause: Error) => setError(cause.message));
  }, [subjectId]);

  useEffect(load, [load, revision]);

  const pending = data?.tasks.some((task) => task.state === "PENDING") ?? false;

  useEffect(() => {
    if (!pending) {
      return;
    }
    const timer = window.setInterval(load, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [pending, load]);

  if (!data || (data.tasks.length === 0 && !data.pageUrl)) {
    return error ? <p role="alert" className="error">{error}</p> : null;
  }

  return (
    <section className="panel" aria-label="Jira und Confluence">
      <div className="toolbar">
        <h2>Jira und Confluence</h2>
        <span className="spacer" />
        <button type="button" onClick={load}>Aktualisieren</button>
      </div>
      {error && <p role="alert" className="error">{error}</p>}
      {data.pageUrl && (
        <p>
          <a href={data.pageUrl} target="_blank" rel="noreferrer">Seite in Confluence öffnen</a>
        </p>
      )}
      <ul className="task-list">
        {data.tasks.map((task) => (
          <li key={task.id}>
            <span className={`task-state task-${task.state}`}>{STATE_LABELS[task.state]}</span>
            <span>
              {KIND_LABELS[task.kind]}
              {task.target && <> <code>{task.target}</code></>}
            </span>
            {task.state === "PENDING" && task.attempts > 0 && (
              <span className="meta">
                Versuch {task.attempts + 1} um {new Date(task.nextAttemptAt).toLocaleTimeString("de-DE")}
              </span>
            )}
            {task.lastError && <span className="meta">{task.lastError}</span>}
            {task.state === "FAILED" && me.roles.includes("REVIEWER") && (
              <button
                type="button"
                onClick={() => {
                  setError(null);
                  api.retryIntegrationTask(task.id).then(load).catch((cause: Error) => setError(cause.message));
                }}
              >
                Erneut versuchen
              </button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
