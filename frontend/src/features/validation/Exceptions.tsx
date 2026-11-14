import { useCallback, useEffect, useState } from 'react';
import { api, Page } from '../../lib/api';
import { FlowContext, message } from '../workspace/flow';
import { Issue } from './Validation';

export default function Exceptions({ flow, setError }: { flow: FlowContext; setError: (e: string) => void }) {
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
      <p className="muted">A reviewer approves the <b>exclusion</b> of invalid rows; invalidity itself is never waived. Valid rows can never be excluded (§4.4). Every invalid row needs an approved exclusion before the manifest can freeze.</p>
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
