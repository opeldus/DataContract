import { useState } from "react";
import type { Me, Reviewable, ReviewActions } from "./api";
import { EVENT_LABELS } from "./labels";

/** Authoring events decide who may no longer approve; the backend enforces the same rule. */
const AUTHORING_EVENTS = new Set(["CREATED", "EDITED", "IMPORTED", "SUBMITTED"]);

function workedOn(item: Reviewable, username: string | null) {
  let worked = false;
  for (const event of item.events) {
    if (event.type === "PUBLISHED") {
      worked = false;
    } else if (AUTHORING_EVENTS.has(event.type) && event.actor === username) {
      worked = true;
    }
  }
  return worked;
}

interface Props<T extends Reviewable> {
  item: T;
  actions: ReviewActions<T>;
  me: Me;
  dirty: boolean;
  /** The part of the item a new comment refers to, e.g. the selected rule. */
  target?: { id: string; label: string } | null;
  targetLabel?: (targetId: string | null) => string | null;
  onUpdated: (item: T) => void;
  run: (action: () => Promise<void>) => void;
}

export function ReviewPanel<T extends Reviewable>({
  item,
  actions,
  me,
  dirty,
  target,
  targetLabel,
  onUpdated,
  run,
}: Props<T>) {
  const [comment, setComment] = useState("");
  const isEditor = me.roles.includes("EDITOR");
  const isReviewer = me.roles.includes("REVIEWER");
  const errorCount = item.issues.filter((issue) => issue.severity === "ERROR").length;
  const selfReview = workedOn(item, me.username);

  function act(action: () => Promise<T>) {
    run(async () => onUpdated(await action()));
  }

  return (
    <section className="panel" aria-label="Review">
      <h2>Review</h2>
      <div className="actions">
        {isEditor && (item.status === "DRAFT" || item.status === "CHANGES_REQUESTED") && (
          <button
            type="button"
            className="primary"
            disabled={dirty || errorCount > 0}
            title={dirty ? "Bitte zuerst speichern" : errorCount > 0 ? `${errorCount} Fehler offen` : undefined}
            onClick={() => act(actions.submit)}
          >
            Zum Review einreichen
          </button>
        )}
        {isReviewer && item.status === "IN_REVIEW" && (
          <>
            <button
              type="button"
              className="primary"
              disabled={selfReview}
              title={selfReview ? "Vier-Augen-Prinzip: Du hast daran mitgearbeitet" : undefined}
              onClick={() => act(() => actions.approve(item.contentHash))}
            >
              Freigeben
            </button>
            <button
              type="button"
              disabled={!comment.trim()}
              title={comment.trim() ? undefined : "Bitte unten eine Begründung eintragen"}
              onClick={() =>
                act(async () => {
                  const updated = await actions.requestChanges(comment);
                  setComment("");
                  return updated;
                })
              }
            >
              Änderung anfordern
            </button>
          </>
        )}
        {isReviewer && item.status === "APPROVED" && (
          <button type="button" className="primary" onClick={() => act(actions.publish)}>
            Nach Git veröffentlichen
          </button>
        )}
        {(isEditor || isReviewer) &&
          (item.status === "IN_REVIEW" || item.status === "APPROVED" || item.status === "PUBLISHED") && (
            <button type="button" onClick={() => act(actions.reopen)}>
              Wieder öffnen
            </button>
          )}
      </div>

      {item.publishedCommit && (
        <p className="meta">
          Veröffentlicht: Version {item.publishedVersion}, Commit <code>{item.publishedCommit.slice(0, 10)}</code>
        </p>
      )}

      <label className="field">
        <span>Kommentar{target ? ` zu ${target.label}` : ""}</span>
        <textarea rows={3} value={comment} onChange={(event) => setComment(event.target.value)} />
      </label>
      <button
        type="button"
        disabled={!comment.trim()}
        onClick={() =>
          act(async () => {
            const updated = await actions.comment(comment, target?.id ?? null);
            setComment("");
            return updated;
          })
        }
      >
        Kommentieren
      </button>

      <h3>Verlauf</h3>
      <ol className="history">
        {[...item.events].reverse().map((event) => {
          const label = targetLabel?.(event.ruleId);
          return (
            <li key={event.id}>
              <strong>{event.actor}</strong> {EVENT_LABELS[event.type] ?? event.type}
              {label && <> ({label})</>}
              <time dateTime={event.createdAt}>{new Date(event.createdAt).toLocaleString("de-DE")}</time>
              {event.comment && event.type !== "PUBLISHED" && <p>{event.comment}</p>}
            </li>
          );
        })}
      </ol>
    </section>
  );
}
