import { useEffect, useState } from 'react';
import { api, Page } from '../../lib/api';
import { pollUntil } from '../../lib/format';
import { message, SectionProps } from '../workspace/flow';

export interface Issue { issueId: string; kind: string; sourceRowId: string; dataRowNumber: number; code: string; field: string; message: string }

export default function Validation({ projectId, flow, patch, setError }: SectionProps) {
  const [snapshot, setSnapshot] = useState<{ status: string; counts: string } | null>(null);
  const [issues, setIssues] = useState<Issue[]>([]);
  const [issuePage, setIssuePage] = useState(0);
  const [hasMoreIssues, setHasMoreIssues] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setSnapshot(null);
    setIssues([]);
    setIssuePage(0);
    setHasMoreIssues(false);
    if (!flow.snapshotId) return;

    api.get<{ status: string; counts: string }>(`/api/v1/validation-snapshots/${flow.snapshotId}`)
      .then((s) => {
        if (!cancelled) {
          setSnapshot(s);
          if (s.status === 'COMPLETED') {
            api.get<Page<Issue>>(`/api/v1/validation-snapshots/${flow.snapshotId}/issues?page=0&size=50`)
              .then((res) => {
                if (!cancelled) {
                  setIssues(res.content);
                  setHasMoreIssues(res.content.length === 50);
                }
              })
              .catch(() => undefined);
          }
        }
      })
      .catch(() => undefined);

    return () => { cancelled = true; };
  }, [flow.snapshotId]);

  const loadIssues = async (snapshotId: string, pageNum: number) => {
    const res = await api.get<Page<Issue>>(`/api/v1/validation-snapshots/${snapshotId}/issues?page=${pageNum}&size=50`);
    setIssues(res.content);
    setHasMoreIssues(res.content.length === 50);
  };

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
        setIssuePage(0);
        await loadIssues(result.snapshotId, 0);
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
      {issues.length > 0 && (
        <>
          <IssueTable issues={issues} />
          <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
            <button disabled={issuePage === 0} onClick={() => { const p = Math.max(0, issuePage - 1); setIssuePage(p); if (flow.snapshotId) loadIssues(flow.snapshotId, p); }}>Previous</button>
            <span>Page {issuePage + 1}</span>
            <button disabled={!hasMoreIssues} onClick={() => { const p = issuePage + 1; setIssuePage(p); if (flow.snapshotId) loadIssues(flow.snapshotId, p); }}>Next</button>
          </div>
        </>
      )}
    </div>
  );
}

export interface RowCounts { raw: number; valid: number; invalid: number }

export function CountsCard({ title, counts }: { title: string; counts: RowCounts }) {
  return (
    <div className="stat"><span className="muted">{title}</span>
      <b>{counts.valid} accepted</b>
      <span className="muted">raw {counts.raw} · invalid {counts.invalid} · raw = accepted + excluded must hold after review</span></div>
  );
}

export function IssueTable({ issues }: { issues: Issue[] }) {
  return (
    <table><thead><tr><th>Dataset</th><th>Row</th><th>Code</th><th>Field</th><th>Message</th></tr></thead>
      <tbody>{issues.map((i) => (
        <tr key={i.issueId}><td>{i.kind}</td><td>{i.dataRowNumber}</td><td><span className="badge bad">{i.code}</span></td><td>{i.field}</td><td>{i.message}</td></tr>
      ))}</tbody></table>
  );
}
