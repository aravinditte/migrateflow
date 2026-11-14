import { useEffect, useState } from 'react';
import { api, Page } from '../../lib/api';
import { formatInstant } from '../../lib/format';
import { message } from '../workspace/flow';

export default function Audit({ projectId, setError }: { projectId: string; setError: (e: string) => void }) {
  const [events, setEvents] = useState<{ id: string; actorEmail: string; action: string; entityType: string; occurredAt: string }[]>([]);
  const [page, setPage] = useState(0);
  const pageSize = 50;

  useEffect(() => {
    let cancelled = false;
    api.get<Page<{ id: string; actorEmail: string; action: string; entityType: string; occurredAt: string }>>(
      `/api/v1/projects/${projectId}/audit-events?page=${page}&size=${pageSize}`)
      .then((p) => {
        if (!cancelled) setEvents(p.content);
      })
      .catch((e) => {
        if (!cancelled) setError(message(e));
      });
    return () => { cancelled = true; };
  }, [projectId, page, setError]);

  return (
    <div className="card">
      <h2>Audit — append-only timeline</h2>
      <table><thead><tr><th>Time (UTC)</th><th>Actor</th><th>Action</th><th>Entity</th></tr></thead>
        <tbody>{events.map((e) => (
          <tr key={e.id}><td>{formatInstant(e.occurredAt)}</td><td>{e.actorEmail}</td><td>{e.action}</td><td>{e.entityType}</td></tr>
        ))}</tbody></table>
      <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
        <button className="secondary" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>Previous</button>
        <span className="muted">Page {page + 1}</span>
        <button className="secondary" disabled={events.length < pageSize} onClick={() => setPage((p) => p + 1)}>Next</button>
      </div>
    </div>
  );
}
