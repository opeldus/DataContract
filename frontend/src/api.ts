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

export interface MergeConflict {
  id: string;
  itemId: string | null;
  field: string | null;
  label: string;
  ours: string | null;
  theirs: string | null;
  removedUpstream: boolean;
  removedHere: boolean;
  upstreamVersion: string;
}

/** What the review panel needs from anything that goes through the review workflow. */
export interface Reviewable {
  key: string;
  releaseId: string | null;
  releaseName: string | null;
  archived: boolean;
  conflicts: MergeConflict[];
  status: MappingStatus;
  publishedVersion: string | null;
  publishedCommit: string | null;
  issues: ValidationIssue[];
  events: ReviewEvent[];
  contentHash: string;
}

export interface MappingSummary {
  id: string;
  key: string;
  inherited: boolean;
  originRelease: string | null;
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
  contractKey: string | null;
  contractName: string | null;
  contractVersion: string | null;
}

export interface ProductSummary {
  id: string;
  key: string;
  inherited: boolean;
  originRelease: string | null;
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
  contractKey: string;
  name: string;
  domain: string | null;
  publishedVersions: string[];
  plannedVersion: string | null;
}

export interface ReleaseItem {
  kind: "CONTRACT" | "PRODUCT";
  id: string;
  key: string;
  name: string;
  version: string;
  status: MappingStatus;
  openConflicts: number;
}

export interface Release {
  id: string;
  name: string;
  title: string;
  baseId: string | null;
  baseName: string | null;
  state: "OPEN" | "RELEASED";
  branch: string;
  createdBy: string;
  createdAt: string;
  releasedAt: string | null;
  releasedCommit: string | null;
  items: ReleaseItem[];
  closeBlockers: string[];
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

export type ChangeImpact = "DOCUMENTATION" | "CONTENT" | "BREAKING";

export interface ContractChange {
  ruleId: string | null;
  code: string;
  impact: ChangeImpact;
  column: string | null;
  message: string;
  before: string | null;
  after: string | null;
}

export interface AffectedProduct {
  productId: string;
  productName: string;
  productTitle: string;
  pinnedVersion: string | null;
  direct: boolean;
}

export interface ChangeReport {
  baselineVersion: string | null;
  baselineAvailable: boolean;
  changes: ContractChange[];
  affected: AffectedProduct[];
}

export interface DdlStatus {
  configured: boolean;
  revision: string | null;
  refreshedAt: string | null;
  tableCount: number;
  warnings: string[];
  error: string | null;
}

export interface IntegrationStatus {
  jira: boolean;
  confluence: boolean;
  publicUrl: boolean;
}

export interface RequirementSuggestion {
  key: string;
  summary: string;
}

export interface IntegrationTask {
  id: string;
  kind: "ISSUE_LINK" | "ISSUE_UNLINK" | "DOCUMENTATION_PAGE" | "GIT_FORWARD";
  target: string | null;
  state: "PENDING" | "DONE" | "FAILED";
  attempts: number;
  lastError: string | null;
  nextAttemptAt: string;
  updatedAt: string;
}

export interface SubjectTasks {
  pageUrl: string | null;
  tasks: IntegrationTask[];
}

export interface TraceEntry {
  key: string;
  url: string | null;
  check: "FOUND" | "NOT_FOUND" | "UNKNOWN" | "INVALID_FORMAT";
  summary: string | null;
  status: string | null;
  statusCategory: string | null;
  type: string | null;
  rules: {
    mappingId: string;
    mappingName: string;
    contractName: string;
    contractVersion: string;
    status: MappingStatus;
    targetColumn: string | null;
  }[];
  products: { productId: string; productName: string; productTitle: string }[];
}

export interface Trace {
  requirements: TraceEntry[];
  rulesWithoutRequirement: number;
}

/** The same review endpoints exist below /api/mappings/{id} and /api/products/{id}. */
export interface ReviewActions<T> {
  submit: () => Promise<T>;
  approve: (contentHash: string) => Promise<T>;
  requestChanges: (comment: string) => Promise<T>;
  comment: (comment: string, ruleId: string | null) => Promise<T>;
  reopen: () => Promise<T>;
  publish: () => Promise<T>;
  resolveConflict: (conflictId: string, takeTheirs: boolean) => Promise<T>;
}

export function reviewActions<T>(basePath: string): ReviewActions<T> {
  return {
    submit: () => jsonRequest<T>(`${basePath}/submit`, "POST"),
    approve: (contentHash) => jsonRequest<T>(`${basePath}/approve`, "POST", { contentHash }),
    requestChanges: (comment) => jsonRequest<T>(`${basePath}/request-changes`, "POST", { comment }),
    comment: (comment, ruleId) => jsonRequest<T>(`${basePath}/comments`, "POST", { comment, ruleId }),
    reopen: () => jsonRequest<T>(`${basePath}/reopen`, "POST"),
    publish: () => jsonRequest<T>(`${basePath}/publish`, "POST"),
    resolveConflict: (conflictId, takeTheirs) =>
      jsonRequest<T>(`${basePath}/conflicts/${conflictId}/resolve`, "POST", { takeTheirs }),
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
  platform: string | null;
  plannedIn: string | null;
}

export interface ColumnSuggestion {
  name: string;
  nativeType: string | null;
  nullable: boolean;
  description: string | null;
  plannedIn: string | null;
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

function releaseQuery(release: string | null): string {
  return release ? `?${new URLSearchParams({ release })}` : "";
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

  listMappings: (release: string | null) => request<MappingSummary[]>(`/api/mappings${releaseQuery(release)}`),
  createMapping: (name: string, contractName: string, releaseId: string | null) =>
    jsonRequest<MappingDetail>("/api/mappings", "POST", { name, contractName, releaseId }),
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

  integrationStatus: () => request<IntegrationStatus>("/api/integrations/status"),
  searchRequirements: (q: string) =>
    request<RequirementSuggestion[]>(`/api/requirements/search?${new URLSearchParams({ q })}`),
  trace: (release: string | null) => request<Trace>(`/api/requirements${releaseQuery(release)}`),
  integrationTasks: (subjectId: string) =>
    request<SubjectTasks>(`/api/integrations/tasks?${new URLSearchParams({ subjectId })}`),
  retryIntegrationTask: (id: string) => jsonRequest<void>(`/api/integrations/tasks/${id}/retry`, "POST"),

  changes: (id: string) => request<ChangeReport>(`/api/mappings/${id}/changes`),
  ddlStatus: () => request<DdlStatus>("/api/ddl/status"),
  consumers: (id: string) => request<ContractConsumer[]>(`/api/mappings/${id}/consumers`),

  listProducts: (release: string | null) => request<ProductSummary[]>(`/api/products${releaseQuery(release)}`),
  createProduct: (name: string, productName: string, releaseId: string | null) =>
    jsonRequest<ProductDetail>("/api/products", "POST", { name, productName, releaseId }),
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
  contracts: (release: string | null) => request<ContractOption[]>(`/api/contracts${releaseQuery(release)}`),
  graph: (release: string | null) => request<Graph>(`/api/graph${releaseQuery(release)}`),

  listReleases: () => request<Release[]>("/api/releases"),
  createRelease: (name: string, title: string, baseId: string | null) =>
    jsonRequest<Release>("/api/releases", "POST", { name, title, baseId }),
  closeRelease: (id: string) => jsonRequest<Release>(`/api/releases/${id}/close`, "POST"),
  forkMapping: (releaseId: string, key: string) =>
    jsonRequest<{ id: string }>(`/api/releases/${releaseId}/mappings/${key}/fork`, "POST"),
  forkProduct: (releaseId: string, key: string) =>
    jsonRequest<{ id: string }>(`/api/releases/${releaseId}/products/${key}/fork`, "POST"),

  searchDatasets: (q: string, release: string | null) =>
    request<DatasetSuggestion[]>(`/api/catalog/datasets?${new URLSearchParams({ q, ...(release ? { release } : {}) })}`),
  searchColumns: (dataset: string, q: string, release: string | null) =>
    request<ColumnSuggestion[]>(`/api/catalog/columns?${new URLSearchParams({ dataset, q, ...(release ? { release } : {}) })}`),
};

export type EtlJobKind = "PARALLEL" | "SEQUENCE" | "UNKNOWN";

export interface EtlJobSummary {
  id: string;
  name: string;
  kind: EtlJobKind;
  category: string | null;
  stages: number;
  warnings: number;
  sourceFile: string;
  importedBy: string;
  importedAt: string;
}

export interface EtlTableAccess {
  name: string;
  access: "READ" | "WRITE";
  kind: "TABLE" | "FILE";
  operation: string;
}

export interface EtlStage {
  id: string;
  name: string;
  oleType: string | null;
  stageType: string | null;
  tables: EtlTableAccess[];
  sql: string[];
  calledJob: string | null;
}

export interface EtlLink {
  from: string;
  to: string;
  name: string | null;
  condition: string | null;
}

export interface EtlJobDetail {
  id: string;
  job: {
    name: string;
    kind: EtlJobKind;
    category: string | null;
    description: string | null;
    stages: EtlStage[];
    links: EtlLink[];
    warnings: string[];
  };
  sourceFile: string;
  importedBy: string;
  importedAt: string;
  sequence: string;
}

export interface EtlImportReport {
  imported: string[];
  unreadable: { path: string; content: string }[];
  warnings: string[];
}

export interface EtlNode {
  id: string;
  kind: "JOB" | "SEQUENCE" | "MISSING_JOB" | "TABLE" | "FILE";
  label: string;
  jobId: string | null;
}

export interface EtlEdge {
  from: string;
  to: string;
  kind: "READS" | "WRITES" | "STARTS";
}

export interface EtlGraph {
  nodes: EtlNode[];
  edges: EtlEdge[];
}

export const etlApi = {
  jobs: () => request<EtlJobSummary[]>("/api/etl/jobs"),
  job: (id: string) => request<EtlJobDetail>(`/api/etl/jobs/${id}`),
  remove: (id: string) => request<void>(`/api/etl/jobs/${id}`, { method: "DELETE" }),
  graph: () => request<EtlGraph>("/api/etl/graph"),
  sequence: (node: string) =>
    request<{ mermaid: string }>(`/api/etl/sequence?${new URLSearchParams({ node })}`),
  importFile: (file: File) => {
    const body = new FormData();
    body.append("file", file);
    return request<EtlImportReport>("/api/etl/imports", { method: "POST", body });
  },
};
