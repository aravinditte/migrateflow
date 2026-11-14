import { ShowcaseOverview } from '../../lib/showcase';
import { formatYen } from '../../lib/format';

function Stat({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="stat">
      <b>{value}</b>
      <div>{label}</div>
      <div className="muted">{hint}</div>
    </div>
  );
}

export default function ShowcaseMetrics({ overview }: { overview: ShowcaseOverview }) {
  const v = overview.validation;
  if (!v) return null;
  return (
    <section>
      <div className="grid">
        <Stat label="Customers accepted" value={String(v.acceptedCustomers)}
          hint={`of ${v.rawCustomers.toLocaleString()} source rows`} />
        <Stat label="Orders accepted" value={String(v.acceptedOrders)}
          hint={`of ${v.rawOrders.toLocaleString()} source rows`} />
        <Stat label="Rows excluded by reviewer" value={String(v.excludedCustomers + v.excludedOrders)}
          hint={`${v.excludedCustomers} customers · ${v.excludedOrders} orders`} />
        <Stat label="Accepted order total" value={overview.money ? formatYen(overview.money.writtenTotalYen) : '—'}
          hint="PostgreSQL BIGINT reconciliation sum" />
        <Stat label="Rehearsal" value={overview.rehearsal?.status ?? '—'}
          hint={`reconciliation ${overview.rehearsal?.reconciliation ?? '—'}`} />
        <Stat label="Final cutover" value={overview.cutover?.status ?? '—'}
          hint={`reconciliation ${overview.cutover?.reconciliation ?? '—'}`} />
      </div>
      <p className="muted">
        Every figure above is read live from persisted PostgreSQL artifacts (validation snapshot, run records,
        reconciliation reports). Integrity status: <strong>{overview.integrity}</strong>
        {overview.verification?.manifestFingerprint && (
          <> · Manifest fingerprint <span className="mono">{overview.verification.manifestFingerprint}…</span></>
        )}
        {overview.integrityProblems.length > 0 && (
          <span className="error"> — {overview.integrityProblems.join('; ')}</span>
        )}
      </p>
    </section>
  );
}
