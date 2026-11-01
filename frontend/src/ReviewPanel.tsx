import { useState } from "react";
import { api, type MappingDetail, type Me, ruleIndexOf } from "./api";
import { EVENT_LABELS } from "./labels";

/** Authoring events decide who may no longer approve; the backend enforces the same rule. */
const AUTHORING_EVENTS = new Set(["CREATED", "EDITED", "IMPORTED", "SUBMITTED"]);

function workedOnMapping(mapping: MappingDetail, username: string | null) {
  let worked = false;
  for (const event of mapping.events) {
    if (event.type === "PUBLISHED") {
      worked = false;
    } else if (AUTHORING_EVENTS.has(event.type) && event.actor === username) {
      worked = true;
    }
  }
  return worked;
}

interface Props {
  mapping: MappingDetail;
  me: Me;
  dirty: boolean;
  selectedRuleId: string | null;
  onUpdated: (mapping: MappingDetail) => void;
  run: (action: () => Promise<void>) => void;
}

export function ReviewPanel({ mapping, me, dirty, selectedRuleId, onUpdated, run }: Props) {
  const [comment, setComment] = useState("");
  const isEditor = me.roles.includes("EDITOR");
  const isReviewer = me.roles.includes("REVIEWER");
  const errorCount = mapping.issues.filter((issue) => issue.severity === "ERROR").length;
  const selectedRuleIndex = ruleIndexOf(mapping, selectedRuleId);
  const selfReview = workedOnMapping(mapping, me.username);

  function act(action: () => Promise<MappingDetail>) {
    run(async () => onUpdated(await action()));
  }

  return (
    <section className="panel" aria-label="Review">
      <h2>Review</h2>
      <div className="actions">
        {isEditor && (mapping.status === "DRAFT" || mapping.status === "CHANGES_REQUESTED") && (
          <button
            type="button"
            className="primary"
            disabled={dirty || errorCount > 0}
            title={dirty ? "Bitte zuerst speichern" : errorCount > 0 ? `${errorCount} Fehler offen` : undefined}
            onClick={() => act(() => api.submit(mapping.id))}
          >
            Zum Review einreichen
          </button>
        )}
        {isReviewer && mapping.status === "IN_REVIEW" && (
          <>
            <button
              type="button"
              className="primary"
              disabled={selfReview}
              title={selfReview ? "Vier-Augen-Prinzip: Du hast am Mapping mitgearbeitet" : undefined}
              onClick={() => act(() => api.approve(mapping.id, mapping.contentHash))}
            >
              Freigeben
            </button>
            <button
              type="button"
              disabled={!comment.trim()}
              title={comment.trim() ? undefined : "Bitte unten eine Begründung eintragen"}
              onClick={() =>
                act(async () => {
                  const updated = await api.requestChanges(mapping.id, comment);
                  setComment("");
                  return updated;
                })
              }
            >
              Änderung anfordern
            </button>
          </>
        )}
        {isReviewer && mapping.status === "APPROVED" && (
          <button type="button" className="primary" onClick={() => act(() => api.publish(mapping.id))}>
            Nach Git veröffentlichen
          </button>
        )}
        {(isEditor || isReviewer) &&
          (mapping.status === "IN_REVIEW" || mapping.status === "APPROVED" || mapping.status === "PUBLISHED") && (
            <button type="button" onClick={() => act(() => api.reopen(mapping.id))}>
              Wieder öffnen
            </button>
          )}
      </div>

      {mapping.publishedCommit && (
        <p className="meta">
          Veröffentlicht: Version {mapping.publishedVersion}, Commit <code>{mapping.publishedCommit.slice(0, 10)}</code>
        </p>
      )}

      <label className="field">
        <span>
          Kommentar{selectedRuleIndex >= 0 ? ` zu Regel ${selectedRuleIndex + 1}` : " zum Mapping"}
        </span>
        <textarea rows={3} value={comment} onChange={(event) => setComment(event.target.value)} />
      </label>
      <button
        type="button"
        disabled={!comment.trim()}
        onClick={() =>
          act(async () => {
            const updated = await api.comment(mapping.id, comment, selectedRuleIndex >= 0 ? selectedRuleId : null);
            setComment("");
            return updated;
          })
        }
      >
        Kommentieren
      </button>

      <h3>Verlauf</h3>
      <ol className="history">
        {[...mapping.events].reverse().map((event) => {
          const ruleIndex = ruleIndexOf(mapping, event.ruleId);
          return (
            <li key={event.id}>
              <strong>{event.actor}</strong> {EVENT_LABELS[event.type] ?? event.type}
              {ruleIndex >= 0 && <> (Regel {ruleIndex + 1})</>}
              <time dateTime={event.createdAt}>{new Date(event.createdAt).toLocaleString("de-DE")}</time>
              {event.comment && event.type !== "PUBLISHED" && <p>{event.comment}</p>}
            </li>
          );
        })}
      </ol>
    </section>
  );
}
