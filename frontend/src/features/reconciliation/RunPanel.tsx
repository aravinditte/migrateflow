import { useCallback, useEffect, useState } from 'react';
import { api } from '../../lib/api';
import { formatYen, shortHash } from '../../lib/format';
import { message } from '../workspace/flow';

export interface RunView { runId: string; kind: string; status: string; processedRows: number; expectedAcceptedRows: number; currentStep: string; restartEligible: boolean; errorMessage: string }

export interface ReconSummary {
  customers: { raw: number; accepted: number; excluded: number; written: number };
  orders: { raw: number; accepted: number; excluded: number; written: number; acceptedAmountYen: number; writtenAmountYen: number };
  differences: string[];
}

/** Live run status + independent reconciliation evidence for one run. */
export default function RunPanel({ runId, setError }: { runId: string; setError: (e: string) => void }) {
  const [run, setRun] = useState<RunView | null>(null);
  const [recon, setRecon] = useState<{ status: string; summary: string; evidenceHash: string } | null>(null);
  const [restarting, setRestarting] = useState(false);
  const [restartError, setRestartError] = useState<string | null>(null);
  useEffect(() => {
    setRun(null);
    setRecon(null);
    setRestartError(null);
  }, [runId]);

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
  const isPostCutoverTampered = run?.status === 'VERIFIED' && recon?.status === 'FAIL';
  return (
    <div>
      {isPostCutoverTampered && (
        <div className="error" style={{ marginBottom: '1rem' }}>
          <b>Integrity Alert:</b> Post-cutover target data modification detected! While the cutover run initially completed as <b>VERIFIED</b>, subsequent independent reconciliation has <b>FAILED</b>. Review differences below.
        </div>
      )}
      {run && (
        <p>Run Status <span className={`badge ${run.status === 'VERIFIED' ? 'ok' : run.status.includes('FAILED') ? 'bad' : ''}`}>{run.status}</span>
          {' '}· processed {run.processedRows}/{run.expectedAcceptedRows}
          {run.currentStep ? ` · step ${run.currentStep}` : ''}
          {run.errorMessage ? <span className="muted"> · {run.errorMessage}</span> : ''}</p>
      )}
      {run?.restartEligible && (
        <p>
          <button
            disabled={restarting}
            onClick={async () => {
              setRestarting(true);
              setRestartError(null);
              try {
                await api.post(`/api/v1/runs/${runId}/restart`);
                await refresh();
              } catch (e: any) {
                setRestartError(message(e));
              } finally {
                setRestarting(false);
              }
            }}
          >
            {restarting ? 'Restarting…' : 'Restart this run (same identity)'}
          </button>
        </p>
      )}
      {restartError && <div className="error" style={{ marginBottom: '1rem' }}>{restartError}</div>}
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
