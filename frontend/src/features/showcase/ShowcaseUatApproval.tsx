import { showcaseApi } from '../../lib/showcase';
import { formatInstant, shortHash } from '../../lib/format';
import { SectionState, useShowcase } from './useShowcase';

export default function ShowcaseUatApproval() {
  const load = useShowcase(() => showcaseApi.uat(), []);
  return (
    <section className="card">
      <h2>Automated synthetic UAT &amp; approval</h2>
      <p className="muted">
        These five checks were executed by a script against the real rehearsal artifacts and recorded
        under the REVIEWER role. A PASS exists only where the check actually passed; this is{' '}
        <strong>not</strong> human business sign-off. The approval below was submitted by a different
        actor than the engineer who froze the manifest — separation of duties is enforced by the backend,
        and the approval binds the manifest, the rehearsal report and this exact UAT evidence hash
        (<span className="mono">…</span> verified again at cutover preflight).
      </p>
      <SectionState load={load}>
        {(data) => (
          <>
            <table>
              <thead><tr><th>Case</th><th>Result</th><th>What was actually checked</th><th>Recorded by</th><th>At</th></tr></thead>
              <tbody>
                {data.cases.map((c) => (
                  <tr key={c.caseKey}>
                    <td className="mono">{c.caseKey}</td>
                    <td className={c.latestResult === 'PASS' ? 'ok' : 'error'}>{c.latestResult || '—'}</td>
                    <td>{c.latestActualResult}</td>
                    <td>{c.latestExecutedByRole} (automated)</td>
                    <td>{c.latestExecutedAt ? formatInstant(c.latestExecutedAt) : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {data.defects.length > 0 && (
              <p>Defects: {data.defects.map((d) => `${d.severity} ${d.status} — ${d.description}`).join('; ')}</p>
            )}
            <p>
              Approval: <strong>{data.approvalStatus || '—'}</strong>
              {data.approvedByRole && <> by {data.approvedByRole} role (automated demo actor)</>}
              {data.approvedAt && <> at {formatInstant(data.approvedAt)}</>} · UAT evidence hash{' '}
              <span className="mono">{shortHash(data.uatEvidenceHash)}…</span>
            </p>
          </>
        )}
      </SectionState>
    </section>
  );
}
