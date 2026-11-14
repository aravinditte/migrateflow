import { useCallback, useEffect, useRef, useState } from 'react';
import { api, Page } from '../lib/api';
import { formatInstant, formatYen, pollUntil, shortHash } from '../lib/format';

export interface FlowContext {
  bundleId: string;
  mappingRevisionId: string;
  ruleSetRevisionId: string;
  snapshotId: string;
  manifestId: string;
  manifestHash: string;
  rehearsalRunId: string;
  cutoverRunId: string;
  approvalId: string;
}

const emptyFlow: FlowContext = {
  bundleId: '', mappingRevisionId: '', ruleSetRevisionId: '', snapshotId: '',
  manifestId: '', manifestHash: '', rehearsalRunId: '', cutoverRunId: '', approvalId: '',
};

interface Project { id: string; name: string; description: string; status: string; windowNote: string }
interface Issue { issueId: string; kind: string; sourceRowId: string; dataRowNumber: number; code: string; field: string; message: string }
interface RunView { runId: string; kind: string; status: string; processedRows: number; expectedAcceptedRows: number; currentStep: string; restartEligible: boolean; errorMessage: string }
interface UatCase { caseId: string; caseKey: string; title: string; expectedResult: string; required: boolean; latestResult: string }
interface Defect { defectId: string; severity: string; status: string; description: string; resolutionNote: string }

const TABS = ['Intake', 'Mapping', 'Validation', 'Exceptions', 'Manifest', 'Rehearsal', 'UAT', 'Approval', 'Cutover', 'Audit'] as const;

export default function Workspace({ project, role }: { project: Project; role: string }) {
  const [tab, setTab] = useState<(typeof TABS)[number]>('Intake');
  const [flow, setFlow] = useState<FlowContext>(emptyFlow);
  const [error, setError] = useState('');
  const patch = (p: Partial<FlowContext>) => setFlow((f) => ({ ...f, ...p }));

  return (
    <div>
      <div className="card">
        <h2>{project.name} <span className="badge">{project.status}</span></h2>
        <p className="muted">{project.description} {project.windowNote ? `· Window: ${project.windowNote}` : ''}</p>
        <p className="muted">You are acting as <b>{role}</b>. Engineers prepare, reviewers decide and approve, auditors inspect.</p>
      </div>
      {error && <div className="error">{error}</div>}
      <div className="tabs">
        {TABS.map((t) => (
          <button key={t} className={tab === t ? 'active' : ''} onClick={() => setTab(t)}>{t}</button>
        ))}
      </div>
      {tab === 'Intake' && <Intake projectId={project.id} flow={flow} patch={patch} setError={setError} />}
      {tab === 'Mapping' && <Mapping projectId={project.id} flow={flow} patch={patch} setError={setError} />}
      {tab === 'Validation' && <Validation projectId={project.id} flow={flow} patch={patch} setError={setError} />}
      {tab === 'Exceptions' && <Exceptions flow={flow} setError={setError} />}
      {tab === 'Manifest' && <ManifestTab projectId={project.id} flow={flow} patch={patch} setError={setError} />}
      {tab === 'Rehearsal' && <Rehearsal flow={flow} patch={patch} setError={setError} />}
      {tab === 'UAT' && <Uat flow={flow} setError={setError} />}
      {tab === 'Approval' && <Approval flow={flow} patch={patch} setError={setError} />}
      {tab === 'Cutover' && <Cutover flow={flow} patch={patch} setError={setError} />}
      {tab === 'Audit' && <Audit projectId={project.id} setError={setError} />}
    </div>
  );
}

type SectionProps = { projectId: string; flow: FlowContext; patch: (p: Partial<FlowContext>) => void; setError: (e: string) => void };

function Intake({ projectId, flow, patch, setError }: SectionProps) {
  const customersRef = useRef<HTMLInputElement>(null);
  const ordersRef = useRef<HTMLInputElement>(null);
  const [exportedAt, setExportedAt] = useState('');
  const [profile, setProfile] = useState<{ files: { kind: string; filename: string; sha256: string; rowCount: number; bytes: number }[] } | null>(null);
  const [busy, setBusy] = useState(false);

  const upload = async () => {
    const customers = customersRef.current?.files?.[0];
    const orders = ordersRef.current?.files?.[0];
    if (!customers || !orders) { setError('Choose both the customers and orders CSV files.'); return; }
    setBusy(true); setError('');
    try {
      const form = new FormData();
      form.append('customers', customers);
      form.append('orders', orders);
      if (exportedAt) form.append('exportedAt', new Date(exportedAt).toISOString());
      const result = await api.postForm<{ bundleId: string }>(`/api/v1/projects/${projectId}/bundles`, form);
      patch({ bundleId: result.bundleId });
      const prof = await api.get(`/api/v1/bundles/${result.bundleId}/profile`);
      setProfile(prof as typeof profile);
    } catch (e) { setError(message(e)); } finally { setBusy(false); }
  };

  return (
    <div className="card">
      <h2>Intake — paired CSV upload</h2>
      <p className="muted">Files are stored privately and immutably; SHA-256 and row provenance are recorded. A malformed file fails the whole upload.</p>
      <label>Customers CSV<input ref={customersRef} type="file" accept=".csv" /></label>
      <label>Orders CSV<input ref={ordersRef} type="file" accept=".csv" /></label>
      <label>Declared export timestamp<input type="datetime-local" value={exportedAt} onChange={(e) => setExportedAt(e.target.value)} /></label>
      <p><button onClick={upload} disabled={busy}>{busy ? 'Uploading…' : 'Upload bundle'}</button></p>
      {flow.bundleId && <p className="muted">Bundle <span className="mono">{flow.bundleId}</span></p>}
      {profile && (
        <table><thead><tr><th>Kind</th><th>File</th><th>Rows</th><th>Bytes</th><th>SHA-256</th></tr></thead>
          <tbody>{profile.files.map((f) => (
            <tr key={f.kind}><td>{f.kind}</td><td>{f.filename}</td><td>{f.rowCount}</td><td>{f.bytes}</td><td className="mono">{shortHash(f.sha256)}…</td></tr>
          ))}</tbody></table>
      )}
    </div>
  );
}

function Mapping({ projectId, flow, patch, setError }: SectionProps) {
  const [definition, setDefinition] = useState('');
  const [published, setPublished] = useState<{ revisionNo: number; definitionHash: string } | null>(null);
  const publish = async () => {
    setError('');
    try {
      const body = definition.trim() ? JSON.parse(definition) : undefined;
      const result = await api.post<{ revisionId: string; ruleSetRevisionId: string; revisionNo: number; definitionHash: string }>(
        `/api/v1/projects/${projectId}/mapping-revisions`, body);
      patch({ mappingRevisionId: result.revisionId, ruleSetRevisionId: result.ruleSetRevisionId });
      setPublished({ revisionNo: result.revisionNo, definitionHash: result.definitionHash });
    } catch (e) { setError(message(e)); }
  };
  return (
    <div className="card">
      <h2>Mapping — transformation revisions</h2>
      <p className="muted">Publish the default spec mapping (TRIM/UPPERCASE ids, lowercase email, yyyy/MM/dd dates, N/P/S/C status map), or paste a revised mapping definition JSON. Published revisions are immutable.</p>
      <label>Mapping definition JSON (optional)<textarea rows={8} value={definition} onChange={(e) => setDefinition(e.target.value)} placeholder="Leave empty to publish the default mapping" /></label>
      <p><button onClick={publish}>Publish mapping revision</button></p>
      {flow.mappingRevisionId && <p className="muted">Revision <span className="mono">{flow.mappingRevisionId}</span>{published ? ` · #${published.revisionNo} · hash ${shortHash(published.definitionHash)}…` : ''}</p>}
    </div>
  );
}

function Validation({ projectId, flow, patch, setError }: SectionProps) {
  const [snapshot, setSnapshot] = useState<{ status: string; counts: string } | null>(null);
  const [issues, setIssues] = useState<Issue[]>([]);
  const start = async () => {
    setError('');
    try {
      const result = await api.post<{ snapshotId: string }>(`/api/v1/projects/${projectId}/validation-snapshots`, {
        bundleId: flow.bundleId, mappingRevisionId: flow.mappingRevisionId,
        ruleSetRevisionId: flow.ruleSetRevisionId || undefined,
      });
      patch({ snapshotId: result.snapshotId });
      const done = await pollUntil(
        () => api.get<{ status: string; counts: string }>(`/api/v1/validation-snapshots/${result.snapshotId}`),
        (s) => s.status === 'COMPLETED' || s.status === 'FAILED');
      setSnapshot(done);
      if (done.status === 'COMPLETED') {
        const page = await api.get<Page<Issue>>(`/api/v1/validation-snapshots/${result.snapshotId}/issues?size=100`);
        setIssues(page.content);
      }
    } catch (e) { setError(message(e)); }
  };
  const counts = snapshot?.counts ? (JSON.parse(snapshot.counts) as { customers: RowCounts; orders: RowCounts }) : null;
  return (
    <div className="card">
      <h2>Validation — deterministic snapshot</h2>
      <p className="muted">Needs a bundle and a mapping revision. Validation produces an immutable snapshot; invalid rows are never written anywhere.</p>
      <p><button onClick={start} disabled={!flow.bundleId || !flow.mappingRevisionId}>Start validation</button></p>
      {snapshot && <p>Status <span className={`badge ${snapshot.status === 'COMPLETED' ? 'ok' : ''}`}>{snapshot.status}</span></p>}
      {counts && (
        <div className="grid">
          <CountsCard title="Customers" counts={counts.customers} />
          <CountsCard title="Orders" counts={counts.orders} />
        </div>
      )}
      {issues.length > 0 && <IssueTable issues={issues} />}
    </div>
  );
}

interface RowCounts { raw: number; valid: number; invalid: number }
function CountsCard({ title, counts }: { title: string; counts: RowCounts }) {
  return (
    <div className="stat"><span className="muted">{title}</span>
      <b>{counts.valid} accepted</b>
      <span className="muted">raw {counts.raw} · invalid {counts.invalid} · raw = accepted + excluded must hold after review</span></div>
  );
}
function IssueTable({ issues }: { issues: Issue[] }) {
  return (
    <table><thead><tr><th>Dataset</th><th>Row</th><th>Code</th><th>Field</th><th>Message</th></tr></thead>
      <tbody>{issues.map((i) => (
        <tr key={i.issueId}><td>{i.kind}</td><td>{i.dataRowNumber}</td><td><span className="badge bad">{i.code}</span></td><td>{i.field}</td><td>{i.message}</td></tr>
      ))}</tbody></table>
  );
}

function Exceptions({ flow, setError }: { flow: FlowContext; setError: (e: string) => void }) {
  const [issues, setIssues] = useState<Issue[]>([]);
  const [reason, setReason] = useState('Confirmed against the source export; row is intentionally omitted');
  const [decided, setDecided] = useState<Record<string, string>>({});
  const load = useCallback(async () => {
    if (!flow.snapshotId) return;
    const page = await api.get<Page<Issue>>(`/api/v1/validation-snapshots/${flow.snapshotId}/issues?size=200`);
    setIssues(page.content);
  }, [flow.snapshotId]);
  useEffect(() => { load().catch((e) => setError(message(e))); }, [load, setError]);

  const invalidRows = new Map<string, Issue[]>();
  for (const issue of issues) {
    const key = `${issue.kind}:${issue.sourceRowId}`;
    invalidRows.set(key, [...(invalidRows.get(key) ?? []), issue]);
  }
  const decide = async (kind: string, sourceRowId: string) => {
    setError('');
    try {
      await api.post(`/api/v1/validation-snapshots/${flow.snapshotId}/exclusions`, {
        dataset: kind, sourceRowId, decision: 'EXCLUDE', reason,
      });
      setDecided((d) => ({ ...d, [`${kind}:${sourceRowId}`]: 'EXCLUDE' }));
    } catch (e) { setError(message(e)); }
  };
  return (
    <div className="card">
      <h2>Exception review — approve omissions (REVIEWER)</h2>
      <p className="muted">A reviewer approves the <b>exclusion</b> of invalid rows; invalidity itself is never waived. Every invalid row needs an approved exclusion before the manifest can freeze.</p>
      <label>Reason applied to decisions<input value={reason} onChange={(e) => setReason(e.target.value)} /></label>
      {!flow.snapshotId && <p className="muted">Run validation first.</p>}
      <table><thead><tr><th>Dataset</th><th>Row</th><th>Issue codes</th><th>Decision</th></tr></thead>
        <tbody>{[...invalidRows.entries()].map(([key, rowIssues]) => (
          <tr key={key}><td>{rowIssues[0].kind}</td><td>{rowIssues[0].dataRowNumber}</td>
            <td>{rowIssues.map((i) => i.code).join(', ')}</td>
            <td>{decided[key]
              ? <span className="badge ok">EXCLUDE approved</span>
              : <button onClick={() => decide(rowIssues[0].kind, rowIssues[0].sourceRowId)}>Approve exclusion</button>}</td></tr>
        ))}</tbody></table>
    </div>
  );
}

function ManifestTab({ projectId, flow, patch, setError }: SectionProps) {
  const [result, setResult] = useState<{ manifestHash: string; fingerprint: string; acceptedCustomers: number; acceptedOrders: number } | null>(null);
  const freeze = async () => {
    setError('');
    if (!window.confirm(`Freeze the manifest for snapshot ${flow.snapshotId}? This binds the exact files, mappings and exclusions.`)) return;
    try {
      const frozen = await api.post<{ manifestId: string; manifestHash: string; fingerprint: string; acceptedCustomers: number; acceptedOrders: number }>(
        `/api/v1/projects/${projectId}/manifests`, {
          bundleId: flow.bundleId, mappingRevisionId: flow.mappingRevisionId,
          ruleSetRevisionId: flow.ruleSetRevisionId, validationSnapshotId: flow.snapshotId,
        });
      patch({ manifestId: frozen.manifestId, manifestHash: frozen.manifestHash });
      setResult(frozen);
    } catch (e) { setError(message(e)); }
  };
  return (
    <div className="card">
      <h2>Manifest — freeze the plan</h2>
      <p className="muted">Freezing binds source hashes, mapping and rule revisions, the validation snapshot, the approved exclusion set and the target contract into one reproducible digest.</p>
      <p><button onClick={freeze} disabled={!flow.snapshotId}>Freeze manifest</button></p>
      {result && (
        <div>
          <p>Accepted: <b>{result.acceptedCustomers} customers</b> · <b>{result.acceptedOrders} orders</b></p>
          <p className="mono">fingerprint {result.fingerprint}<br />{result.manifestHash}</p>
        </div>
      )}
      {flow.manifestId && <p className="muted">Manifest <span className="mono">{flow.manifestId}</span></p>}
    </div>
  );
}

function RunPanel({ runId, setError }: { runId: string; setError: (e: string) => void }) {
  const [run, setRun] = useState<RunView | null>(null);
  const [recon, setRecon] = useState<{ status: string; summary: string; evidenceHash: string } | null>(null);
  const refresh = useCallback(async () => {
    if (!runId) return;
    const current = await api.get<RunView>(`/api/v1/runs/${runId}`);
    setRun(current);
    if (current.status === 'VERIFIED' || current.status === 'VERIFICATION_FAILED') {
      setRecon(await api.get(`/api/v1/runs/${runId}/reconciliation`));
    }
  }, [runId]);
  useEffect(() => {
    refresh().catch((e) => setError(message(e)));
    const timer = setInterval(() => { refresh().catch(() => undefined); }, 2500);
    return () => clearInterval(timer);
  }, [refresh, setError]);
  if (!runId) return null;
  const summary = recon?.summary ? (JSON.parse(recon.summary) as ReconSummary) : null;
  return (
    <div>
      {run && (
        <p>Status <span className={`badge ${run.status === 'VERIFIED' ? 'ok' : run.status.includes('FAILED') ? 'bad' : ''}`}>{run.status}</span>
          {' '}· processed {run.processedRows}/{run.expectedAcceptedRows}
          {run.currentStep ? ` · step ${run.currentStep}` : ''}
          {run.errorMessage ? <span className="muted"> · {run.errorMessage}</span> : ''}</p>
      )}
      {run?.restartEligible && (
        <p><button onClick={async () => { await api.post(`/api/v1/runs/${runId}/restart`); refresh().catch(() => undefined); }}>
          Restart this run (same identity)</button></p>
      )}
      {summary && (
        <div className="grid">
          <div className="stat"><span className="muted">Customers written</span><b>{summary.customers.written}</b>
            <span className="muted">accepted {summary.customers.accepted} · excluded {summary.customers.excluded}</span></div>
          <div className="stat"><span className="muted">Orders written</span><b>{summary.orders.written}</b>
            <span className="muted">accepted {summary.orders.accepted} · total {formatYen(summary.orders.writtenAmountYen)}</span></div>
          <div className="stat"><span className="muted">Reconciliation</span><b>{recon?.status}</b>
            <span className="muted">evidence {shortHash(recon?.evidenceHash)}… · differences {summary.differences.length}</span></div>
        </div>
      )}
      {summary && summary.differences.length > 0 && (
        <table><tbody>{summary.differences.map((d, i) => <tr key={i}><td>{d}</td></tr>)}</tbody></table>
      )}
      <p className="muted">Evidence: <a href={`/api/v1/runs/${runId}/report`} target="_blank" rel="noreferrer">JSON</a>
        {' · '}<a href={`/api/v1/runs/${runId}/report?format=csv`}>CSV</a></p>
    </div>
  );
}

interface ReconSummary {
  customers: { raw: number; accepted: number; excluded: number; written: number };
  orders: { raw: number; accepted: number; excluded: number; written: number; acceptedAmountYen: number; writtenAmountYen: number };
  differences: string[];
}

function Rehearsal({ flow, patch, setError }: Omit<SectionProps, 'projectId'>) {
  const launch = async () => {
    setError('');
    try {
      const run = await api.post<{ runId: string }>(`/api/v1/manifests/${flow.manifestId}/rehearsals`);
      patch({ rehearsalRunId: run.runId });
    } catch (e) { setError(message(e)); }
  };
  return (
    <div className="card">
      <h2>Rehearsal — isolated run</h2>
      <p className="muted">Writes only into run-scoped rehearsal tables; the final target stays empty. Verification is by independent reconciliation, not batch status alone.</p>
      <p><button onClick={launch} disabled={!flow.manifestId}>Launch rehearsal</button></p>
      <RunPanel runId={flow.rehearsalRunId} setError={setError} />
    </div>
  );
}

function Uat({ flow, setError }: { flow: FlowContext; setError: (e: string) => void }) {
  const [cases, setCases] = useState<UatCase[]>([]);
  const [defects, setDefects] = useState<Defect[]>([]);
  const [hash, setHash] = useState('');
  const load = useCallback(async () => {
    if (!flow.rehearsalRunId) return;
    const data = await api.get<{ cases: UatCase[]; defects: Defect[]; uatEvidenceHash: string }>(
      `/api/v1/rehearsals/${flow.rehearsalRunId}/uat`);
    setCases(data.cases); setDefects(data.defects); setHash(data.uatEvidenceHash);
  }, [flow.rehearsalRunId]);
  useEffect(() => { load().catch((e) => setError(message(e))); }, [load, setError]);

  const execute = async (caseKey: string, result: string) => {
    setError('');
    try {
      await api.post(`/api/v1/rehearsals/${flow.rehearsalRunId}/uat-executions`, {
        caseKey, result, actualResult: result === 'PASS' ? 'Verified in rehearsal data' : 'See defect',
      });
      await load();
    } catch (e) { setError(message(e)); }
  };
  const openDefect = async (caseKey: string) => {
    setError('');
    try {
      await api.post(`/api/v1/rehearsals/${flow.rehearsalRunId}/defects`, {
        caseKey, severity: 'CRITICAL', description: `Blocking issue found during ${caseKey}`,
      });
      await load();
    } catch (e) { setError(message(e)); }
  };
  const patchDefect = async (defectId: string, status: string) => {
    setError('');
    try {
      await api.patch(`/api/v1/defects/${defectId}`, {
        status, resolutionNote: status === 'RESOLVED' ? 'Fixed by revised plan; ready for retest' : undefined,
      });
      await load();
    } catch (e) { setError(message(e)); }
  };
  return (
    <div className="card">
      <h2>UAT — business acceptance (REVIEWER)</h2>
      {!flow.rehearsalRunId && <p className="muted">Run a verified rehearsal first; its cases appear here.</p>}
      <table><thead><tr><th>Case</th><th>Expected</th><th>Latest</th><th>Actions</th></tr></thead>
        <tbody>{cases.map((c) => (
          <tr key={c.caseId}><td>{c.title}{c.required ? ' *' : ''}</td><td className="muted">{c.expectedResult}</td>
            <td><span className={`badge ${c.latestResult === 'PASS' ? 'ok' : c.latestResult === 'FAIL' ? 'bad' : ''}`}>{c.latestResult}</span></td>
            <td><button className="secondary" onClick={() => execute(c.caseKey, 'PASS')}>Pass</button>{' '}
              <button className="secondary" onClick={() => execute(c.caseKey, 'FAIL')}>Fail</button>{' '}
              <button className="secondary" onClick={() => openDefect(c.caseKey)}>Open defect</button></td></tr>
        ))}</tbody></table>
      {defects.length > 0 && (
        <table><thead><tr><th>Severity</th><th>Status</th><th>Description</th><th>Actions</th></tr></thead>
          <tbody>{defects.map((d) => (
            <tr key={d.defectId}><td>{d.severity}</td><td>{d.status}</td><td>{d.description}</td>
              <td>{d.status === 'OPEN' && <button className="secondary" onClick={() => patchDefect(d.defectId, 'RESOLVED')}>Resolve</button>}
                {d.status === 'RESOLVED' && <button className="secondary" onClick={() => patchDefect(d.defectId, 'CLOSED')}>Close after retest</button>}</td></tr>
          ))}</tbody></table>
      )}
      {hash && <p className="muted">UAT evidence digest <span className="mono">{hash}</span></p>}
    </div>
  );
}

function Approval({ flow, patch, setError }: Omit<SectionProps, 'projectId'>) {
  const [comment, setComment] = useState('Counts, values, exclusions and critical UAT checks verified');
  const decide = async (decision: 'APPROVE' | 'REJECT') => {
    setError('');
    try {
      const recon = await api.get<{ reportId: string }>(`/api/v1/runs/${flow.rehearsalRunId}/reconciliation`);
      const uat = await api.get<{ uatEvidenceHash: string }>(`/api/v1/rehearsals/${flow.rehearsalRunId}/uat`);
      if (!window.confirm(`${decision} manifest ${flow.manifestId} bound to rehearsal ${flow.rehearsalRunId}?`)) return;
      const result = await api.post<{ approvalId: string }>(`/api/v1/manifests/${flow.manifestId}/approvals`, {
        decision, rehearsalRunId: flow.rehearsalRunId, reconciliationReportId: recon.reportId,
        uatEvidenceHash: uat.uatEvidenceHash, comment,
      });
      patch({ approvalId: result.approvalId });
    } catch (e) { setError(message(e)); }
  };
  return (
    <div className="card">
      <h2>Approval — independent sign-off (REVIEWER)</h2>
      <p className="muted">Approval binds the manifest, the verified rehearsal, its PASS reconciliation report and the current UAT evidence digest. The manifest creator cannot approve, and the approver cannot launch the cutover.</p>
      <label>Comment<input value={comment} onChange={(e) => setComment(e.target.value)} /></label>
      <p><button onClick={() => decide('APPROVE')} disabled={!flow.rehearsalRunId}>Approve for cutover</button>{' '}
        <button className="secondary" onClick={() => decide('REJECT')}>Reject</button></p>
      {flow.approvalId && <p className="muted">Approval <span className="mono">{flow.approvalId}</span></p>}
    </div>
  );
}

function Cutover({ flow, patch, setError }: Omit<SectionProps, 'projectId'>) {
  const launch = async () => {
    setError('');
    if (!window.confirm(`Launch the final cutover for manifest ${flow.manifestId}? This writes to the simulated final target and cannot be repeated.`)) return;
    try {
      const result = await api.post<{ runId: string }>(`/api/v1/manifests/${flow.manifestId}/cutovers`, {
        approvalId: flow.approvalId,
      }, { 'Idempotency-Key': crypto.randomUUID() });
      patch({ cutoverRunId: result.runId });
    } catch (e) { setError(message(e)); }
  };
  return (
    <div className="card">
      <h2>Cutover — controlled final migration (ENGINEER, not the approver)</h2>
      <p className="muted">Preflight locks the single final target, checks approval and emptiness, and reserves the slot atomically. A failed run restarts under the same identity — never as a new run.</p>
      <label>Approval ID<input value={flow.approvalId} onChange={(e) => patch({ approvalId: e.target.value })} placeholder="Approval UUID from the Approval tab" /></label>
      <p><button onClick={launch} disabled={!flow.manifestId || !flow.approvalId}>Launch cutover</button></p>
      <RunPanel runId={flow.cutoverRunId} setError={setError} />
    </div>
  );
}

function Audit({ projectId, setError }: { projectId: string; setError: (e: string) => void }) {
  const [events, setEvents] = useState<{ id: string; actorEmail: string; action: string; entityType: string; occurredAt: string }[]>([]);
  useEffect(() => {
    api.get<Page<{ id: string; actorEmail: string; action: string; entityType: string; occurredAt: string }>>(
      `/api/v1/projects/${projectId}/audit-events?size=100`)
      .then((p) => setEvents(p.content))
      .catch((e) => setError(message(e)));
  }, [projectId, setError]);
  return (
    <div className="card">
      <h2>Audit — append-only timeline</h2>
      <table><thead><tr><th>Time (UTC)</th><th>Actor</th><th>Action</th><th>Entity</th></tr></thead>
        <tbody>{events.map((e) => (
          <tr key={e.id}><td>{formatInstant(e.occurredAt)}</td><td>{e.actorEmail}</td><td>{e.action}</td><td>{e.entityType}</td></tr>
        ))}</tbody></table>
    </div>
  );
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : 'Request failed';
}
