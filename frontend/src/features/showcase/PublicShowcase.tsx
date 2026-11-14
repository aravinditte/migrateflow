import { showcaseApi } from '../../lib/showcase';
import { useShowcase } from './useShowcase';
import ShowcaseHero from './ShowcaseHero';
import ShowcaseMetrics from './ShowcaseMetrics';
import ShowcaseTimeline from './ShowcaseTimeline';
import ShowcaseValidation from './ShowcaseValidation';
import ShowcaseBeforeAfter from './ShowcaseBeforeAfter';
import ShowcaseReconciliation from './ShowcaseReconciliation';
import ShowcaseUatApproval from './ShowcaseUatApproval';
import ShowcaseAudit from './ShowcaseAudit';
import ShowcaseArchitecture from './ShowcaseArchitecture';

/**
 * Default anonymous experience (Automated Showcase spec 2/6): a completed,
 * evidence-backed migration, read live from the backend. If the backend is
 * down or no verified scenario exists, this page says so plainly instead
 * of showing placeholder success.
 */
export default function PublicShowcase({ onSignIn }: { onSignIn: () => void }) {
  const load = useShowcase(() => showcaseApi.overview(), []);

  if (load.state === 'loading') {
    return (
      <main>
        <h1>Migrate<span>Flow</span></h1>
        <p className="muted">Loading live migration evidence from the backend…</p>
      </main>
    );
  }
  if (load.state === 'error') {
    return (
      <main>
        <h1>Migrate<span>Flow</span></h1>
        <p className="error">
          Live evidence temporarily unavailable — the backend could not be reached, so no results are
          shown. MigrateFlow never substitutes cached or fabricated figures.
        </p>
        <button className="secondary" onClick={onSignIn}>Operator sign in</button>
      </main>
    );
  }

  const overview = load.data;
  if (overview.setupState === 'NOT_READY' || overview.setupState === 'PREPARING') {
    return (
      <main>
        <h1>Migrate<span>Flow</span></h1>
        <p>
          Verifiable data migration from legacy CSV to PostgreSQL, executed by a real Spring Boot +
          Spring Batch backend over synthetic source data.
        </p>
        <p className="muted">
          The public showcase scenario has not finished bootstrapping yet
          {overview.setupState === 'PREPARING' && overview.setupStep ? ` (currently at stage: ${overview.setupStep})` : ''}.
          Results appear here only after a real run has been executed and verified — nothing is pre-filled.
        </p>
        <button className="secondary" onClick={onSignIn}>Operator sign in</button>
      </main>
    );
  }
  if (overview.setupState === 'FAILED') {
    return (
      <main>
        <h1>Migrate<span>Flow</span></h1>
        <p className="error">
          The showcase bootstrap failed and was recorded as FAILED rather than presented as success.
          {overview.failureMessage ? ` Reason: ${overview.failureMessage}` : ''}
        </p>
        <button className="secondary" onClick={onSignIn}>Operator sign in</button>
      </main>
    );
  }

  return (
    <main className="showcase">
      <ShowcaseHero overview={overview} onSignIn={onSignIn} />
      {overview.setupState === 'STALE' && (
        <p className="error">
          Integrity alert: the historical cutover evidence below is real, but a fresh lineage re-check
          no longer passes ({overview.integrityProblems.join('; ')}). The historical verification time
          still applies to the original run.
        </p>
      )}
      <ShowcaseMetrics overview={overview} />
      <ShowcaseTimeline />
      <ShowcaseValidation />
      <ShowcaseBeforeAfter />
      <ShowcaseReconciliation />
      <ShowcaseUatApproval />
      <ShowcaseAudit />
      <ShowcaseArchitecture />
      <footer className="muted">
        Synthetic data · authentic execution · automated demonstration actors · portfolio demonstration,
        not a production migration service.
      </footer>
    </main>
  );
}
