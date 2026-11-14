import { useCallback, useEffect, useState } from 'react';
import { api, Page } from '../../lib/api';
import { FlowContext, message } from '../workspace/flow';
import { Issue } from './Validation';

export default function Exceptions({ flow, setError }: { flow: FlowContext; setError: (e: string) => void }) {
  const [issues, setIssues] = useState<Issue[]>([]);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [reason, setReason] = useState('Confirmed against the source export; row is intentionally omitted');
  const [decided, setDecided] = useState<Record<string, string>>({});

  useEffect(() => {
    setDecided({});
    setPage(0);
  }, [flow.snapshotId]);

  const load = useCallback(async () => {
    if (!flow.snapshotId) return;
    try {
      const [res, exclRes] = await Promise.all([
        api.get<Page<Issue>>(`/api/v1/validation-snapshots/${flow.snapshotId}/issues?page=${page}&size=50`),
        api.get<{ content: { kind: string; sourceRowId: string; decision: string }[] }>(
          `/api/v1/validation-snapshots/${flow.snapshotId}/exclusions`
        ).catch(() => ({ content: [] })),
      ]);
      setIssues(res.content);
      setHasMore(res.content.length === 50);
      const existingDecisions: Record<string, string> = {};
      for (const d of exclRes.content) {
        existingDecisions[`${d.kind}:${d.sourceRowId}`] = d.decision;
      }
      setDecided((prev) => ({ ...prev, ...existingDecisions }));
    } catch (e) {
      setError(message(e));
    }
  }, [flow.snapshotId, page, setError]);

  useEffect(() => { load(); }, [load]);

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
      <p className="muted">A reviewer approves the <b>exclusion</b> of invalid rows; invalidity itself is never waived. Valid rows can never be excluded (§4.4). Every invalid row needs an approved exclusion before the manifest can freeze.</p>
      <label>Reason applied to decisions<input value={reason} onChange={(e) => setReason(e.target.value)} /></label>
      {!flow.snapshotId && <p className="muted">Run validation first.</p>}
      <table><thead><tr><th>Dataset</th><th>Row</th><th>Issue codes</th><th>Decision</th></tr></thead>
        <tbody>{[...invalidRows.entries()].map(([key, rowIssues]) => (
          <tr key={key}><td>{rowIssues[0].kind}</td><td>{rowIssues[0].dataRowNumber}</td>
            <td>{rowIssues.map((i) => i.code).join(', ')}</td>
            <td>{decided[key] === 'EXCLUDE'
              ? <span className="badge ok">EXCLUDE approved</span>
              : decided[key] === 'KEEP'
              ? <span className="badge warn">KEEP (unresolved)</span>
              : <button onClick={() => decide(rowIssues[0].kind, rowIssues[0].sourceRowId)}>Approve exclusion</button>}</td></tr>
        ))}</tbody></table>
      <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
        <button disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>Previous</button>
        <span>Page {page + 1}</span>
        <button disabled={!hasMore} onClick={() => setPage((p) => p + 1)}>Next</button>
      </div>
    </div>
  );
}
