import { useState } from 'react';
import { api } from '../../lib/api';
import { shortHash } from '../../lib/format';
import { message, SectionProps } from '../workspace/flow';

export default function Mappings({ projectId, flow, patch, setError }: SectionProps) {
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
      <p className="muted">Publish the default spec mapping (TRIM/UPPERCASE ids, lowercase email, yyyy/MM/dd dates, N/P/S/C status map), or paste a revised mapping definition JSON. Published revisions are immutable. Publishing also ensures the project's default rule-set revision (§4.4 semantics) and returns its ID.</p>
      <label>Mapping definition JSON (optional)<textarea rows={8} value={definition} onChange={(e) => setDefinition(e.target.value)} placeholder="Leave empty to publish the default mapping" /></label>
      <p><button onClick={publish}>Publish mapping revision</button></p>
      {flow.mappingRevisionId && <p className="muted">Revision <span className="mono">{flow.mappingRevisionId}</span>{published ? ` · #${published.revisionNo} · hash ${shortHash(published.definitionHash)}…` : ''}</p>}
    </div>
  );
}
