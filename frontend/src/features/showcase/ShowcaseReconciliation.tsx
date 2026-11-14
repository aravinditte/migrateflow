import { ReconciliationView, showcaseApi } from '../../lib/showcase';
import { formatInstant, formatYen, shortHash } from '../../lib/format';
import { SectionState, useShowcase } from './useShowcase';

function Report({ title, report }: { title: string; report: ReconciliationView }) {
  const s = report.summary;
  return (
    <div>
      <h3>{title}: <span className={report.status === 'PASS' ? 'ok' : 'error'}>{report.status}</span></h3>
      <table>
        <tbody>
          <tr><td>Customers accepted → written</td><td>{s.customers.acceptedRows} → {s.customers.writtenRows}</td></tr>
          <tr><td>Orders accepted → written</td><td>{s.orders.acceptedRows} → {s.orders.writtenRows}</td></tr>
          <tr><td>Order total accepted → written</td><td>{formatYen(s.orders.acceptedAmountYen)} → {formatYen(s.orders.writtenAmountYen)}</td></tr>
          <tr><td>Differences</td><td>{s.differences.length === 0 ? 'none' : s.differences.join('; ')}</td></tr>
          <tr><td>Evidence hash</td><td className="mono">{shortHash(report.evidenceHash)}…</td></tr>
          <tr><td>Completed</td><td>{formatInstant(report.completedAt)}</td></tr>
        </tbody>
      </table>
    </div>
  );
}

export default function ShowcaseReconciliation() {
  const rehearsal = useShowcase(() => showcaseApi.reconciliation('rehearsal'), []);
  const cutover = useShowcase(() => showcaseApi.reconciliation('cutover'), []);
  return (
    <section className="card">
      <h2>Reconciliation — independent recount of both targets</h2>
      <p className="muted">
        After each run, the backend recounts the target and compares counts, BIGINT sums and per-row
        provenance against the frozen manifest&apos;s accepted snapshot. These are the stored reports,
        not a recount performed by this page.
      </p>
      <SectionState load={rehearsal}>{(r) => <Report title="Rehearsal report" report={r} />}</SectionState>
      <SectionState load={cutover}>{(r) => <Report title="Final cutover report" report={r} />}</SectionState>
    </section>
  );
}
