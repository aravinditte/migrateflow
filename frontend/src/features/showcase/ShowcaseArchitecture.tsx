import { showcaseApi } from '../../lib/showcase';
import { shortHash } from '../../lib/format';
import { SectionState, useShowcase } from './useShowcase';

interface FieldMapping {
  sourceColumn: string;
  targetField: string;
  steps?: { operation: string; args?: Record<string, string> }[];
  required?: boolean;
}

export function MappingView({ data }: { data: { revisionNo: number; definitionHash: string; ruleHash: string; definition: string } }) {
  let parsed: { customers?: FieldMapping[]; orders?: FieldMapping[] } = {};
  try { parsed = JSON.parse(data.definition) as typeof parsed; } catch { /* fall through to hash-only view */ }
  const rows: { dataset: string; m: FieldMapping }[] = [
    ...(parsed.customers ?? []).map((m) => ({ dataset: 'customers', m })),
    ...(parsed.orders ?? []).map((m) => ({ dataset: 'orders', m })),
  ];
  return rows.length > 0 ? (
    <>
      <table>
        <thead><tr><th>Dataset</th><th>Source column</th><th>Target column</th><th>Transforms</th></tr></thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>
              <td>{r.dataset}</td><td className="mono">{r.m.sourceColumn}</td><td className="mono">{r.m.targetField}</td>
              <td>{(r.m.steps ?? []).map((t) => t.operation).join(' → ')}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted">
        Mapping revision #{data.revisionNo} · definition hash <span className="mono">{shortHash(data.definitionHash)}…</span>{' '}
        · rule-set hash <span className="mono">{shortHash(data.ruleHash)}…</span> — both are inputs to the frozen manifest hash.
      </p>
    </>
  ) : (
    <p className="muted">Mapping revision #{data.revisionNo} · definition hash{' '}
      <span className="mono">{shortHash(data.definitionHash)}…</span></p>
  );
}

function MappingTable() {
  const load = useShowcase(() => showcaseApi.mapping(), []);
  return (
    <SectionState load={load}>
      {(data) => <MappingView data={data} />}
    </SectionState>
  );
}

const REPO = 'https://github.com/aravinditte/migrateflow/blob/main';

export default function ShowcaseArchitecture() {
  return (
    <section className="card">
      <h2>How it works — and where to read the code</h2>
      <p>
        Browser → nginx → Spring Boot API → services → Spring Batch jobs (chunked, restartable) →
        PostgreSQL 16 (control / stage / rehearsal / target schemas). The rehearsal writes to isolated
        rehearsal tables; the cutover writes once to the insert-only final target under a singleton slot
        lock. A frozen manifest (hash of source files, mapping, rules and exclusion set) is the only
        input a run accepts, and every written row lands in an applied-rows ledger that reconciliation
        recounts independently.
      </p>
      <MappingTable />
      <ul>
        <li><a href={`${REPO}/backend/src/main/java/com/migrateflow/execution/BatchConfig.java`} target="_blank" rel="noreferrer">Spring Batch job definition</a></li>
        <li><a href={`${REPO}/backend/src/main/java/com/migrateflow/showcase/ShowcaseQueryService.java`} target="_blank" rel="noreferrer">This page&apos;s read model + lineage verification</a></li>
        <li><a href={`${REPO}/scripts/bootstrap-showcase.py`} target="_blank" rel="noreferrer">The bootstrap that executed this scenario</a></li>
        <li><a href={`${REPO}/SPEC.md`} target="_blank" rel="noreferrer">Full specification</a></li>
      </ul>
      <p className="muted">
        Limitations, stated plainly: single-backend demo deployment, session auth, local file storage for
        uploads, scripted (not human) UAT, and one shared final target — a second cutover is refused by
        design. See <a href={`${REPO}/docs/limitations.md`} target="_blank" rel="noreferrer">docs/limitations.md</a>.
      </p>
    </section>
  );
}
