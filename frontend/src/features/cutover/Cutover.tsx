import { api } from '../../lib/api';
import RunPanel from '../reconciliation/RunPanel';
import { message, SectionProps } from '../workspace/flow';

export default function Cutover({ flow, patch, setError }: Omit<SectionProps, 'projectId'>) {
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
