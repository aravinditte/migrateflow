import { useCallback, useEffect, useState } from 'react';
import { api } from '../../lib/api';
import { FlowContext, message } from '../workspace/flow';
import RehearsalData from './RehearsalData';

export interface UatCase { caseId: string; caseKey: string; title: string; expectedResult: string; required: boolean; latestResult: string }
export interface Defect {
  defectId: string; caseKey: string; severity: string; status: string; description: string;
  resolutionNote: string; resolvedAt: string; retestRequired: boolean; retestPassed: boolean;
}

export default function Uat({ flow, setError }: { flow: FlowContext; setError: (e: string) => void }) {
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
    const defaultNote = result === 'PASS'
      ? 'Observed matching target row counts and reconciliation output'
      : 'Discrepancy observed in sample records';
    const observation = (typeof window.prompt === 'function'
      ? window.prompt(`Reviewer observation for ${caseKey} (${result}):`, defaultNote)
      : defaultNote) || defaultNote;
    if (!observation.trim()) {
      setError('Observation is required to record a UAT result');
      return;
    }
    try {
      await api.post(`/api/v1/rehearsals/${flow.rehearsalRunId}/uat-executions`, {
        caseKey, result, actualResult: observation.trim(),
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
    let note: string | undefined = undefined;
    if (status === 'RESOLVED') {
      const defaultNote = 'Fixed by revised plan; ready for retest';
      const entered = typeof window.prompt === 'function'
        ? window.prompt('Resolution note and plan reference:', defaultNote)
        : defaultNote;
      if (!entered || !entered.trim()) {
        setError('Resolution note is required to resolve a defect');
        return;
      }
      note = entered.trim();
    }
    try {
      await api.patch(`/api/v1/defects/${defectId}`, {
        status, resolutionNote: note,
      });
      await load();
    } catch (e) { setError(message(e)); }
  };

  const retestCell = (d: Defect) => {
    if (!d.retestRequired) return <span className="muted">not required</span>;
    if (d.status === 'OPEN') return <span className="muted">after resolution</span>;
    return d.retestPassed
      ? <span className="badge ok">retest PASS recorded</span>
      : <span className="badge bad">awaiting retest</span>;
  };
  const closeAction = (d: Defect) => {
    if (d.status === 'OPEN') {
      return <button className="secondary" onClick={() => patchDefect(d.defectId, 'RESOLVED')}>Resolve</button>;
    }
    if (d.status === 'RESOLVED') {
      if (d.retestRequired && !d.retestPassed) {
        return <span className="muted">Re-run case <b>{d.caseKey}</b> and record <b>Pass</b> above — the defect
          can be closed only after a passing retest (§9).</span>;
      }
      return <button className="secondary" onClick={() => patchDefect(d.defectId, 'CLOSED')}>Close after retest</button>;
    }
    return null;
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
      <RehearsalData runId={flow.rehearsalRunId} setError={setError} />
      {defects.length > 0 && (
        <table><thead><tr><th>Severity</th><th>Case</th><th>Status</th><th>Description</th><th>Retest</th><th>Actions</th></tr></thead>
          <tbody>{defects.map((d) => (
            <tr key={d.defectId}><td>{d.severity}</td><td>{d.caseKey || '—'}</td><td>{d.status}</td><td>{d.description}</td>
              <td>{retestCell(d)}</td><td>{closeAction(d)}</td></tr>
          ))}</tbody></table>
      )}
      {hash && <p className="muted">UAT evidence digest <span className="mono">{hash}</span></p>}
    </div>
  );
}
