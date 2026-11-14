import { describe, expect, it } from 'vitest';
import type { ReconciliationView } from './showcase';

/**
 * Contract fixture test (showcase review S-11): the JSON below is the
 * exact shape ReconciliationService persists into
 * control.reconciliation_reports.summary_json — accepted/written (not
 * acceptedRows/writtenRows), raw/excluded alongside, amounts inside the
 * orders section, and a top-level differences list. If the backend shape
 * ever changes, update this fixture and the ReconciliationView type
 * together; a silent rename is what broke the first showcase build.
 */
const REAL_CUTOVER_SUMMARY = {
  runId: '00000000-0000-0000-0000-000000000001',
  kind: 'CUTOVER',
  status: 'PASS',
  customers: { raw: 1000, accepted: 995, excluded: 5, written: 995 },
  orders: {
    raw: 2000, accepted: 1990, excluded: 10, written: 1990,
    acceptedAmountYen: 21998650, writtenAmountYen: 21998650,
  },
  differences: [] as string[],
};

describe('showcase reconciliation contract', () => {
  it('reads the backend summary shape the UI depends on', () => {
    const view = {
      scope: 'cutover',
      runId: REAL_CUTOVER_SUMMARY.runId,
      status: 'PASS',
      summary: REAL_CUTOVER_SUMMARY,
      evidenceHash: 'deadbeef',
      completedAt: '2026-11-14T00:00:00Z',
    } satisfies ReconciliationView;

    expect(view.summary.customers.accepted).toBe(995);
    expect(view.summary.customers.written).toBe(995);
    expect(view.summary.orders.accepted).toBe(1990);
    expect(view.summary.orders.written).toBe(1990);
    expect(view.summary.orders.acceptedAmountYen).toBe(21998650);
    expect(view.summary.orders.writtenAmountYen).toBe(21998650);
    expect(view.summary.differences).toEqual([]);
  });
});
