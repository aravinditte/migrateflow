import { useEffect, useState } from 'react';
import { api, Page } from '../../lib/api';
import { formatInstant } from '../../lib/format';
import { message } from '../workspace/flow';

export default function Audit({ projectId, setError }: { projectId: string; setError: (e: string) => void }) {
  const [events, setEvents] = useState<{ id: string; actorEmail: string; action: string; entityType: string; occurredAt: string }[]>([]);
  useEffect(() => {
    api.get<Page<{ id: string; actorEmail: string; action: string; entityType: string; occurredAt: string }>>(
      `/api/v1/projects/${projectId}/audit-events?size=100`)
      .then((p) => setEvents(p.content))
      .catch((e) => setError(message(e)));
  }, [projectId, setError]);
  return (
    <div className="card">
      <h2>Audit — append-only timeline</h2>
      <table><thead><tr><th>Time (UTC)</th><th>Actor</th><th>Action</th><th>Entity</th></tr></thead>
        <tbody>{events.map((e) => (
          <tr key={e.id}><td>{formatInstant(e.occurredAt)}</td><td>{e.actorEmail}</td><td>{e.action}</td><td>{e.entityType}</td></tr>
        ))}</tbody></table>
    </div>
  );
}
