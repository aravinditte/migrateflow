import { useState } from 'react';
import { api } from '../../lib/api';
import { message, SectionProps } from '../workspace/flow';

export default function Manifest({ projectId, flow, patch, setError }: SectionProps) {
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
