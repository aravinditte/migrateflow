import { api } from '../../lib/api';
import RunPanel from '../reconciliation/RunPanel';
import { message, SectionProps } from '../workspace/flow';

export default function Rehearsal({ flow, patch, setError }: Omit<SectionProps, 'projectId'>) {
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
