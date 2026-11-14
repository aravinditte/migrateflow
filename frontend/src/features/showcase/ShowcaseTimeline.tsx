import { useState } from 'react';
import { showcaseApi } from '../../lib/showcase';
import { formatInstant } from '../../lib/format';
import { SectionState, useShowcase } from './useShowcase';

const STATUS_ICON: Record<string, string> = {
  COMPLETED: '✓', IN_PROGRESS: '…', FAILED: '✗', PENDING: '○',
};

export default function ShowcaseTimeline() {
  const load = useShowcase(() => showcaseApi.workflow(), []);
  const [open, setOpen] = useState<string | null>(null);
  return (
    <section className="card">
      <h2>Workflow — 11 stages, each backed by a real artifact</h2>
      <SectionState load={load}>
        {(data) => (
          <ol className="timeline">
            {data.stages.map((stage, i) => (
              <li key={stage.key} className="timeline-item">
                <button className="timeline-head secondary" onClick={() => setOpen(open === stage.key ? null : stage.key)}>
                  <span>{i + 1}. {STATUS_ICON[stage.status] ?? '○'} {stage.title}</span>
                  <span className="muted">{stage.occurredAt ? formatInstant(stage.occurredAt) : stage.status}</span>
                </button>
                {open === stage.key && (
                  <div className="timeline-evidence">
                    {Object.entries(stage.evidence).filter(([, val]) => val !== '').map(([k, val]) => (
                      <div key={k}><span className="muted">{k}:</span> <span className="mono">{val}</span></div>
                    ))}
                    {Object.values(stage.evidence).every((val) => val === '') && (
                      <span className="muted">No evidence recorded yet.</span>
                    )}
                  </div>
                )}
              </li>
            ))}
          </ol>
        )}
      </SectionState>
    </section>
  );
}
