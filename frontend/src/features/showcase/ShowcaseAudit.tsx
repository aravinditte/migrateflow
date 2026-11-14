import { useState } from 'react';
import { showcaseApi } from '../../lib/showcase';
import { formatInstant } from '../../lib/format';
import { SectionState, useShowcase } from './useShowcase';

export default function ShowcaseAudit() {
  const [page, setPage] = useState(0);
  const load = useShowcase(() => showcaseApi.audit(page), [page]);
  return (
    <section className="card">
      <h2>Audit trail (sanitized)</h2>
      <p className="muted">
        The project&apos;s real audit events, newest first. Actor emails and internal details are not
        published — only the role that acted. Demonstration actors are automated.
      </p>
      <SectionState load={load}>
        {(data) => (
          <>
            <table>
              <thead><tr><th>When</th><th>Action</th><th>Entity</th><th>Actor role</th></tr></thead>
              <tbody>
                {data.content.map((row, i) => (
                  <tr key={`${row.occurredAt}-${row.action}-${i}`}>
                    <td>{formatInstant(row.occurredAt)}</td>
                    <td className="mono">{row.action}</td>
                    <td>{row.entityType}</td>
                    <td>{row.actorRole}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p>
              <button className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>← Prev</button>{' '}
              <button className="secondary" disabled={data.content.length < data.size} onClick={() => setPage(page + 1)}>Next →</button>
            </p>
          </>
        )}
      </SectionState>
    </section>
  );
}
