import type { MappingStatus } from "./api";

export const STATUS_LABELS: Record<MappingStatus, string> = {
  DRAFT: "Entwurf",
  IN_REVIEW: "Im Review",
  CHANGES_REQUESTED: "Änderung nötig",
  APPROVED: "Freigegeben",
  PUBLISHED: "Veröffentlicht",
};

export const EVENT_LABELS: Record<string, string> = {
  CREATED: "hat das Mapping angelegt",
  EDITED: "hat das Mapping bearbeitet",
  IMPORTED: "hat importiert",
  SUBMITTED: "hat zum Review eingereicht",
  COMMENTED: "hat kommentiert",
  CHANGES_REQUESTED: "hat Änderungen angefordert",
  APPROVED: "hat freigegeben",
  REOPENED: "hat das Mapping wieder geöffnet",
  PUBLISHED: "hat nach Git veröffentlicht",
};
