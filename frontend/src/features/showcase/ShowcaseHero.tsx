import { ShowcaseOverview, showcaseApi } from '../../lib/showcase';
import { formatInstant, formatYen } from '../../lib/format';

export default function ShowcaseHero({ overview, onSignIn }: { overview: ShowcaseOverview; onSignIn?: () => void }) {
  const v = overview.validation;
  const total = v ? v.acceptedCustomers + v.acceptedOrders : 0;
  return (
    <section className="hero">
      <div className="hero-top">
        <h1>Migrate<span>Flow</span></h1>
        {onSignIn && <button className="secondary" onClick={onSignIn}>Operator sign in</button>}
      </div>
      <p className="hero-sub">
        Verifiable data migration from legacy CSV to PostgreSQL — imperfect source exports in,
        a verified cutover out.
      </p>
      {overview.setupState === 'READY' && v && (
        <p>
          <strong>{total.toLocaleString()} records</strong> migrated from synthetic source data using a real
          Spring Boot + Spring Batch backend. Explore validation, exclusions, reconciliation, UAT and final
          cutover evidence — no login required.
        </p>
      )}
      <div className="badges">
        <span className="badge">Synthetic business data</span>
        <span className="badge">Real backend execution</span>
        <span className="badge">Automated demonstration actors</span>
        <span className="badge">{overview.backend.connected ? '● Backend connected' : '● Backend unreachable'}</span>
        {overview.verification?.cutoverReconciliationCompletedAt && (
          <span className="badge">Cutover verified {formatInstant(overview.verification.cutoverReconciliationCompletedAt)}</span>
        )}
      </div>
      <p className="muted">
        Fictional company: Hinata Trading Co. This is a portfolio demonstration, not a production migration
        service. <a href={showcaseApi.evidenceUrl}>Download the evidence JSON</a> ·{' '}
        <a href="https://github.com/aravinditte/migrateflow" target="_blank" rel="noreferrer">Source on GitHub</a>
      </p>
      {overview.money && (
        <p className="muted">Accepted order total {formatYen(overview.money.acceptedTotalYen)} · written{' '}
          {formatYen(overview.money.writtenTotalYen)} — read live from the stored reconciliation report.</p>
      )}
    </section>
  );
}
