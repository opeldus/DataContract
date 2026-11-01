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

/** What the review panel needs from anything that goes through the review workflow. */
export interface Reviewable {
  status: MappingStatus;
  publishedVersion: string | null;
  publishedCommit: string | null;
  issues: ValidationIssue[];
  events: ReviewEvent[];
  contentHash: string;
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

export interface MappingDetail extends Reviewable {
  id: string;
  name: string;
  contractName: string;
  contractVersion: string;
  domain: string | null;
  description: string | null;
  targetTable: string | null;
  ownerTeam: string | null;
  createdBy: string;
  updatedAt: string;
  version: number;
  rules: Rule[];
}

export type PortDirection = "INPUT" | "OUTPUT";

export interface Port {
  id: string | null;
  direction: PortDirection;
  name: string | null;
  contractMappingId: string | null;
  contractName: string | null;
  contractVersion: string | null;
}

export interface ProductSummary {
  id: string;
  name: string;
  productName: string;
  productVersion: string;
  status: MappingStatus;
  ownerTeam: string | null;
  inputPorts: number;
  outputPorts: number;
  updatedAt: string;
}

export interface ProductDetail extends Reviewable {
  id: string;
  name: string;
  productName: string;
  productVersion: string;
  domain: string | null;
  productType: string | null;
  purpose: string | null;
  ownerTeam: string | null;
  createdBy: string;
  updatedAt: string;
  version: number;
  ports: Port[];
}

export interface ContractOption {
  mappingId: string;
  name: string;
  domain: string | null;
  publishedVersions: string[];
}

export interface ContractConsumer {
  productId: string;
  productName: string;
  productTitle: string;
  productStatus: MappingStatus;
  direction: PortDirection;
  portName: string | null;
  contractVersion: string | null;
}

export interface GraphNode {
  id: string;
  kind: "CONTRACT" | "PRODUCT";
  label: string;
  subjectId: string;
  status: MappingStatus;
  version: string;
}

export interface GraphEdge {
  from: string;
  to: string;
  version: string | null;
}

export interface Graph {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

/** The same review endpoints exist below /api/mappings/{id} and /api/products/{id}. */
export interface ReviewActions<T> {
  submit: () => Promise<T>;
  approve: (contentHash: string) => Promise<T>;
  requestChanges: (comment: string) => Promise<T>;
  comment: (comment: string, ruleId: string | null) => Promise<T>;
  reopen: () => Promise<T>;
  publish: () => Promise<T>;
}

export function reviewActions<T>(basePath: string): ReviewActions<T> {
  return {
    submit: () => jsonRequest<T>(`${basePath}/submit`, "POST"),
    approve: (contentHash) => jsonRequest<T>(`${basePath}/approve`, "POST", { contentHash }),
    requestChanges: (comment) => jsonRequest<T>(`${basePath}/request-changes`, "POST", { comment }),
    comment: (comment, ruleId) => jsonRequest<T>(`${basePath}/comments`, "POST", { comment, ruleId }),
    reopen: () => jsonRequest<T>(`${basePath}/reopen`, "POST"),
    publish: () => jsonRequest<T>(`${basePath}/publish`, "POST"),
  };
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
export function ruleIndexOf(mapping: Pick<MappingDetail, "rules">, ruleId: string | null): number {
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

  consumers: (id: string) => request<ContractConsumer[]>(`/api/mappings/${id}/consumers`),

  listProducts: () => request<ProductSummary[]>("/api/products"),
  createProduct: (name: string, productName: string) =>
    jsonRequest<ProductDetail>("/api/products", "POST", { name, productName }),
  getProduct: (id: string) => request<ProductDetail>(`/api/products/${id}`),
  updateProduct: (product: ProductDetail) =>
    jsonRequest<ProductDetail>(`/api/products/${product.id}`, "PUT", {
      version: product.version,
      name: product.name,
      productVersion: product.productVersion,
      domain: product.domain,
      productType: product.productType,
      purpose: product.purpose,
      ownerTeam: product.ownerTeam,
      ports: product.ports,
    }),
  previewProduct: (id: string) => request<PreviewFile[]>(`/api/products/${id}/preview`),
  contracts: () => request<ContractOption[]>("/api/contracts"),
  graph: () => request<Graph>("/api/graph"),

  searchDatasets: (q: string) =>
    request<DatasetSuggestion[]>(`/api/catalog/datasets?${new URLSearchParams({ q })}`),
  searchColumns: (dataset: string, q: string) =>
    request<ColumnSuggestion[]>(`/api/catalog/columns?${new URLSearchParams({ dataset, q })}`),
};
