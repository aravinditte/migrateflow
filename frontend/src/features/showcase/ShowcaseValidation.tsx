import { useState } from 'react';
import { showcaseApi } from '../../lib/showcase';
import { SectionState, useShowcase } from './useShowcase';

export default function ShowcaseValidation() {
  const [page, setPage] = useState(0);
  const load = useShowcase(() => showcaseApi.validation(page), [page]);
  return (
    <section className="card">
      <h2>Validation findings &amp; reviewer exclusions</h2>
      <p className="muted">
        Every invalid source row, the exact rule it broke, and the REVIEWER decision that excluded it.
        A row can carry several issues; exclusion counts distinct rows, never summed issues.
      </p>
      <SectionState load={load}>
        {(data) => (
          <>
            <table>
              <thead>
                <tr><th>Dataset</th><th>Row</th><th>Code</th><th>Field</th><th>Message</th><th>Decision</th></tr>
              </thead>
              <tbody>
                {data.content.map((issue, i) => (
                  <tr key={`${issue.kind}-${issue.dataRowNumber}-${issue.code}-${i}`}>
                    <td>{issue.kind}</td><td>{issue.dataRowNumber}</td><td className="mono">{issue.code}</td>
                    <td>{issue.field}</td><td>{issue.message}</td>
                    <td>{issue.decision
                      ? <span title={issue.decisionReason}>{issue.decision} · {issue.decidedByRole} (automated demo actor)</span>
                      : <span className="muted">undecided</span>}</td>
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
