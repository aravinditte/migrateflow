import { describe, expect, it } from 'vitest';
import { renderToString } from 'react-dom/server';
import { ShowcaseView } from './PublicShowcase';
import type { ShowcaseOverview } from '../../lib/showcase';

const BASE_OVERVIEW: ShowcaseOverview = {
  showcase: 'hinata-trading',
  mode: 'PUBLIC_READ_ONLY',
  dataClassification: 'SYNTHETIC',
  setupState: 'READY',
  setupStep: 'publish',
  integrity: 'OK',
  integrityProblems: [],
  integrityCheckedAt: '2026-11-14T00:00:00Z',
  failureMessage: null,
  backend: { connected: true },
  project: { name: 'Hinata Trading', organization: 'Hinata Trading Co.', createdAt: '2026-11-14T00:00:00Z' },
  validation: {
    rawCustomers: 1000,
    rawOrders: 2000,
    acceptedCustomers: 995,
    acceptedOrders: 1990,
    excludedCustomers: 5,
    excludedOrders: 10,
  },
  rehearsal: { status: 'VERIFIED', reconciliation: 'PASS' },
  cutover: { status: 'VERIFIED', reconciliation: 'PASS' },
  money: {
    currency: 'JPY',
    acceptedTotalYen: 21998650,
    writtenTotalYen: 21998650,
  },
  verification: {
    verifiedAt: '2026-11-14T10:00:00Z',
    cutoverReconciliationCompletedAt: '2026-11-14T09:59:00Z',
    showcaseRegisteredAt: '2026-11-14T10:00:00Z',
    manifestFingerprint: 'abcd1234efgh',
    manifestHash: 'abcd1234efgh5678',
    reportEvidenceHash: '1122334455667788',
  },
};

describe('PublicShowcase UI states', () => {
  it('renders NOT_READY state without placeholder metrics', () => {
    const html = renderToString(
      <ShowcaseView
        overview={{ ...BASE_OVERVIEW, setupState: 'NOT_READY' }}
        onSignIn={() => {}}
      />
    );
    expect(html).toContain('The public showcase scenario has not finished bootstrapping yet');
    expect(html).toContain('Operator sign in');
    expect(html).not.toContain('¥21,998,650');
  });

  it('renders PREPARING state with current stage', () => {
    const html = renderToString(
      <ShowcaseView
        overview={{ ...BASE_OVERVIEW, setupState: 'PREPARING', setupStep: 'validation' }}
        onSignIn={() => {}}
      />
    );
    expect(html).toContain('currently at stage: validation');
    expect(html).not.toContain('¥21,998,650');
  });

  it('renders FAILED state with failure reason', () => {
    const html = renderToString(
      <ShowcaseView
        overview={{
          ...BASE_OVERVIEW,
          setupState: 'FAILED',
          failureMessage: 'rehearsal reconciliation mismatch',
        }}
        onSignIn={() => {}}
      />
    );
    expect(html).toContain('The showcase bootstrap failed and was recorded as FAILED');
    expect(html).toContain('Reason: rehearsal reconciliation mismatch');
  });

  it('renders STALE state with Lineage Integrity Alert and hides detail panels', () => {
    const html = renderToString(
      <ShowcaseView
        overview={{
          ...BASE_OVERVIEW,
          setupState: 'STALE',
          integrity: 'INTEGRITY_ALERT',
          integrityProblems: ['approval decision is not APPROVED (was: REJECTED)'],
        }}
        onSignIn={() => {}}
      />
    );
    expect(html).toContain('Lineage Integrity Alert');
    expect(html).toContain('approval decision is not APPROVED (was: REJECTED)');
    expect(html).toContain('Detail sections');
    expect(html).toContain('temporarily unavailable');
  });

  it('renders READY state with full metrics and architecture', () => {
    const html = renderToString(
      <ShowcaseView overview={BASE_OVERVIEW} onSignIn={() => {}} />
    );
    expect(html).toContain('¥21,998,650');
    expect(html).toContain('995');
    expect(html).toContain('1990');
    expect(html).toContain('Hinata Trading');
    expect(html).not.toContain('Lineage Integrity Alert');
  });
});
