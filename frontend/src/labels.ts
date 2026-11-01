import type { MappingStatus } from "./api";

export const STATUS_LABELS: Record<MappingStatus, string> = {
  DRAFT: "Entwurf",
  IN_REVIEW: "Im Review",
  CHANGES_REQUESTED: "Änderung nötig",
  APPROVED: "Freigegeben",
  PUBLISHED: "Veröffentlicht",
};

export const EVENT_LABELS: Record<string, string> = {
  CREATED: "hat angelegt",
  EDITED: "hat bearbeitet",
  IMPORTED: "hat importiert",
  SUBMITTED: "hat zum Review eingereicht",
  COMMENTED: "hat kommentiert",
  CHANGES_REQUESTED: "hat Änderungen angefordert",
  APPROVED: "hat freigegeben",
  REOPENED: "hat wieder geöffnet",
  PUBLISHED: "hat nach Git veröffentlicht",
};

export const PRODUCT_TYPES: { value: string; label: string }[] = [
  { value: "sourceAligned", label: "Quellnah" },
  { value: "aggregate", label: "Aggregat" },
  { value: "consumerAligned", label: "Abnehmernah" },
];

export function toTechnicalName(name: string) {
  return name
    .toLowerCase()
    .replace(/ä/g, "ae")
    .replace(/ö/g, "oe")
    .replace(/ü/g, "ue")
    .replace(/ß/g, "ss")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}
