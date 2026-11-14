import { useState } from 'react';
import { api } from '../../lib/api';
import { message, SectionProps } from '../workspace/flow';

export default function Approval({ flow, patch, setError }: Omit<SectionProps, 'projectId'>) {
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
