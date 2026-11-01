export type MappingStatus = "DRAFT" | "IN_REVIEW" | "CHANGES_REQUESTED" | "APPROVED" | "PUBLISHED";

export interface Me {
  authenticated: boolean;
  username: string | null;
  displayName: string | null;
  roles: string[];
}

export interface Rule {
  id: string | null;
  sourceDataset: string | null;
  sourceColumn: string | null;
  targetColumn: string | null;
  targetType: string | null;
  required: boolean;
  primaryKey: boolean;
  transformation: string | null;
  description: string | null;
  requirementKey: string | null;
}

export interface ValidationIssue {
  severity: "ERROR" | "WARNING";
  code: string;
  message: string;
  ruleId: string | null;
  field: string | null;
}

export interface ReviewEvent {
  id: string;
  type: string;
  actor: string;
  comment: string | null;
  ruleId: string | null;
  contentHash: string | null;
  createdAt: string;
}

export interface MappingSummary {
  id: string;
  name: string;
  contractName: string;
  contractVersion: string;
  status: MappingStatus;
  ownerTeam: string | null;
  ruleCount: number;
  updatedAt: string;
}

export interface MappingDetail {
  id: string;
  name: string;
  contractName: string;
  contractVersion: string;
  domain: string | null;
  description: string | null;
  targetTable: string | null;
  ownerTeam: string | null;
  status: MappingStatus;
  publishedVersion: string | null;
  publishedCommit: string | null;
  createdBy: string;
  updatedAt: string;
  version: number;
  rules: Rule[];
  issues: ValidationIssue[];
  events: ReviewEvent[];
  contentHash: string;
}

export interface ImportResult {
  imported: number;
  skippedRows: number;
  ignoredColumns: string[];
  mapping: MappingDetail;
}

export interface PreviewFile {
  path: string;
  content: string;
}

export interface DatasetSuggestion {
  name: string;
  platform: string;
}

export interface ColumnSuggestion {
  name: string;
  nativeType: string | null;
  nullable: boolean;
  description: string | null;
}

/** Unsaved rules have no id yet, so a null id must never match them. */
export function ruleIndexOf(mapping: MappingDetail, ruleId: string | null): number {
  return ruleId === null ? -1 : mapping.rules.findIndex((rule) => rule.id === ruleId);
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

function csrfToken(): string | undefined {
  return document.cookie
    .split("; ")
    .find((cookie) => cookie.startsWith("XSRF-TOKEN="))
    ?.slice("XSRF-TOKEN=".length);
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const token = csrfToken();
  if (token && init.method && init.method !== "GET") {
    headers.set("X-XSRF-TOKEN", decodeURIComponent(token));
  }
  const response = await fetch(path, { ...init, headers, credentials: "same-origin" });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new ApiError(problem?.detail ?? `Anfrage fehlgeschlagen (${response.status})`, response.status);
  }
  return response.status === 204 ? (undefined as T) : response.json();
}

function jsonRequest<T>(path: string, method: string, body?: unknown): Promise<T> {
  return request<T>(path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

export const api = {
  me: () => request<Me>("/api/auth/me"),
  login: (username: string, password: string) =>
    request<void>("/api/auth/login", {
      method: "POST",
      body: new URLSearchParams({ username, password }),
    }),
  logout: () => request<void>("/api/auth/logout", { method: "POST" }),

  listMappings: () => request<MappingSummary[]>("/api/mappings"),
  createMapping: (name: string, contractName: string) =>
    jsonRequest<MappingDetail>("/api/mappings", "POST", { name, contractName }),
  getMapping: (id: string) => request<MappingDetail>(`/api/mappings/${id}`),
  updateMapping: (mapping: MappingDetail) =>
    jsonRequest<MappingDetail>(`/api/mappings/${mapping.id}`, "PUT", {
      version: mapping.version,
      name: mapping.name,
      contractVersion: mapping.contractVersion,
      domain: mapping.domain,
      description: mapping.description,
      targetTable: mapping.targetTable,
      ownerTeam: mapping.ownerTeam,
      rules: mapping.rules,
    }),
  importExcel: (id: string, file: File) => {
    const body = new FormData();
    body.append("file", file);
    return request<ImportResult>(`/api/mappings/${id}/import`, { method: "POST", body });
  },
  preview: (id: string) => request<PreviewFile[]>(`/api/mappings/${id}/preview`),

  submit: (id: string) => jsonRequest<MappingDetail>(`/api/mappings/${id}/submit`, "POST"),
  approve: (id: string, contentHash: string) =>
    jsonRequest<MappingDetail>(`/api/mappings/${id}/approve`, "POST", { contentHash }),
  requestChanges: (id: string, comment: string) =>
    jsonRequest<MappingDetail>(`/api/mappings/${id}/request-changes`, "POST", { comment }),
  comment: (id: string, comment: string, ruleId: string | null) =>
    jsonRequest<MappingDetail>(`/api/mappings/${id}/comments`, "POST", { comment, ruleId }),
  reopen: (id: string) => jsonRequest<MappingDetail>(`/api/mappings/${id}/reopen`, "POST"),
  publish: (id: string) => jsonRequest<MappingDetail>(`/api/mappings/${id}/publish`, "POST"),

  searchDatasets: (q: string) =>
    request<DatasetSuggestion[]>(`/api/catalog/datasets?${new URLSearchParams({ q })}`),
  searchColumns: (dataset: string, q: string) =>
    request<ColumnSuggestion[]>(`/api/catalog/columns?${new URLSearchParams({ dataset, q })}`),
};
