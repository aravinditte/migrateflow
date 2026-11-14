import { useState } from 'react';
import { showcaseApi } from '../../lib/showcase';
import { SectionState, useShowcase } from './useShowcase';

export default function ShowcaseBeforeAfter() {
  const [kind, setKind] = useState<'customers' | 'orders'>('customers');
  const [page, setPage] = useState(0);
  const load = useShowcase(() => showcaseApi.records(kind, page), [kind, page]);
  return (
    <section className="card">
      <h2>Before → after: real migrated records</h2>
      <p className="muted">
        Whitelisted synthetic records joined from the stored source file to the final target table.
        Provenance (file, row, run) comes from the target row itself.
      </p>
      <p>
        <button className={kind === 'customers' ? '' : 'secondary'} onClick={() => { setKind('customers'); setPage(0); }}>Customers</button>{' '}
        <button className={kind === 'orders' ? '' : 'secondary'} onClick={() => { setKind('orders'); setPage(0); }}>Orders</button>
      </p>
      <SectionState load={load}>
        {(data) => (
          <>
            {data.content.map((row) => (
              <div key={row.externalId} className="before-after">
                <div>
                  <div className="muted">SOURCE · {row.sourceFile} · row {row.sourceRowNumber}</div>
                  <pre>{JSON.stringify(row.source, null, 1)}</pre>
                </div>
                <div className="arrow">→</div>
                <div>
                  <div className="muted">TARGET · PostgreSQL · run <span className="mono">{row.originRunId.slice(0, 8)}…</span></div>
                  <pre>{JSON.stringify(row.target, null, 1)}</pre>
                </div>
              </div>
            ))}
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
