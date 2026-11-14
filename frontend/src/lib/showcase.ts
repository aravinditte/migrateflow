// Public showcase API client: anonymous, GET-only, no session, no CSRF,
// no fake fallback data. If the backend is unreachable the caller shows an
// honest "evidence unavailable" state (Automated Showcase spec 5.5/13).

export interface ShowcaseOverview {
  showcase: string;
  mode: string;
  dataClassification: string;
  setupState: string;
  setupStep: string | null;
  integrity: string;
  integrityProblems: string[];
  integrityCheckedAt: string;
  failureMessage: string | null;
  backend: { connected: boolean };
  project: { name: string; organization: string; createdAt: string } | null;
  validation: {
    rawCustomers: number; rawOrders: number;
    acceptedCustomers: number; acceptedOrders: number;
    excludedCustomers: number; excludedOrders: number;
  } | null;
  rehearsal: { status: string; reconciliation: string | null } | null;
  cutover: { status: string; reconciliation: string | null } | null;
  money: { currency: string; acceptedTotalYen: number; writtenTotalYen: number } | null;
  verification: {
    verifiedAt: string | null;
    cutoverReconciliationCompletedAt: string | null;
    showcaseRegisteredAt: string | null;
    manifestFingerprint: string | null;
    manifestHash: string | null;
    reportEvidenceHash: string | null;
  } | null;
}

export interface ShowcaseStage {
  key: string; title: string; status: string;
  occurredAt: string | null; evidence: Record<string, string>;
}

export interface ShowcaseIssue {
  kind: string; dataRowNumber: number; code: string; field: string; message: string;
  decision: string; decisionReason: string; decidedByRole: string;
}

export interface ShowcaseRecord {
  kind: string; externalId: string;
  source: Record<string, string>; target: Record<string, string | number>;
  sourceFile: string; sourceRowNumber: number; originRunId: string;
}

export interface ReconciliationView {
  scope: string; runId: string; status: string; evidenceHash: string; completedAt: string;
  // Field names mirror ReconciliationService's persisted summary JSON
  // exactly (accepted/written, not acceptedRows/writtenRows) — guarded by
  // showcase.contract.test.ts against the real backend shape.
  summary: {
    customers: { raw: number; accepted: number; excluded: number; written: number };
    orders: { raw: number; accepted: number; excluded: number; written: number; acceptedAmountYen: number; writtenAmountYen: number };
    differences: string[];
  };
}

export interface UatView {
  cases: {
    caseKey: string; title: string; required: boolean; latestResult: string;
    latestActualResult: string; latestExecutedAt: string | null; latestExecutedByRole: string;
  }[];
  defects: { severity: string; status: string; description: string; caseKey: string }[];
  uatEvidenceHash: string;
  approvalStatus: string; approvedByRole: string; approvedAt: string | null;
}

export interface AuditRow { action: string; entityType: string; actorRole: string; occurredAt: string }

export interface MappingView {
  revisionId: string; revisionNo: number; definitionHash: string; definition: string;
  ruleSetRevisionId: string; ruleHash: string; rules: string;
}

export interface Page<T> { content: T[]; page: number; size: number }

async function guest<T>(path: string): Promise<T> {
  const response = await fetch(path, { method: 'GET' });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return (await response.json()) as T;
}

export const showcaseApi = {
  overview: () => guest<ShowcaseOverview>('/api/v1/showcase/overview'),
  workflow: () => guest<{ setupState: string; stages: ShowcaseStage[] }>('/api/v1/showcase/workflow'),
  validation: (page: number) => guest<Page<ShowcaseIssue>>(`/api/v1/showcase/validation?page=${page}&size=10`),
  mapping: () => guest<MappingView>('/api/v1/showcase/mapping'),
  reconciliation: (scope: 'rehearsal' | 'cutover') =>
    guest<ReconciliationView>(`/api/v1/showcase/reconciliation?scope=${scope}`),
  uat: () => guest<UatView>('/api/v1/showcase/uat'),
  audit: (page: number) => guest<Page<AuditRow>>(`/api/v1/showcase/audit?page=${page}&size=15`),
  records: (kind: 'customers' | 'orders', page: number) =>
    guest<Page<ShowcaseRecord>>(`/api/v1/showcase/records?kind=${kind}&page=${page}&size=5`),
  evidenceUrl: '/api/v1/showcase/evidence',
};
