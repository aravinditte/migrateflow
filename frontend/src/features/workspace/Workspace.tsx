import { useEffect, useState } from 'react';
import { api } from '../../lib/api';
import Audit from '../audit/Audit';
import Approval from '../cutover/Approval';
import Cutover from '../cutover/Cutover';
import Intake from '../intake/Intake';
import Mappings from '../mappings/Mappings';
import Rehearsal from '../rehearsal/Rehearsal';
import Uat from '../uat/Uat';
import Exceptions from '../validation/Exceptions';
import Manifest from '../validation/Manifest';
import Validation from '../validation/Validation';
import { emptyFlow, FlowContext, Project } from './flow';

const TABS = ['Intake', 'Mapping', 'Validation', 'Exceptions', 'Manifest', 'Rehearsal', 'UAT', 'Approval', 'Cutover', 'Audit'] as const;

/** Project workspace shell: owns the cross-stage flow identifiers and the
 *  tab routing; each stage lives in its own feature module (spec 15). */
export default function Workspace({ project, role }: { project: Project; role: string }) {
  const [tab, setTab] = useState<(typeof TABS)[number]>('Intake');
  const [flow, setFlow] = useState<FlowContext>(emptyFlow);
  const [error, setError] = useState('');
  const patch = (p: Partial<FlowContext>) => {
    setFlow((prev) => {
      const next = { ...prev, ...p };
      if (p.bundleId && p.bundleId !== prev.bundleId) {
        next.snapshotId = '';
        next.manifestId = '';
        next.manifestHash = '';
        next.rehearsalRunId = '';
        next.approvalId = '';
        next.cutoverRunId = '';
      } else if (p.mappingRevisionId && p.mappingRevisionId !== prev.mappingRevisionId) {
        next.snapshotId = '';
        next.manifestId = '';
        next.manifestHash = '';
        next.rehearsalRunId = '';
        next.approvalId = '';
        next.cutoverRunId = '';
      } else if (p.snapshotId && p.snapshotId !== prev.snapshotId) {
        next.manifestId = '';
        next.manifestHash = '';
        next.rehearsalRunId = '';
        next.approvalId = '';
        next.cutoverRunId = '';
      } else if (p.manifestId && p.manifestId !== prev.manifestId) {
        next.rehearsalRunId = '';
        next.approvalId = '';
        next.cutoverRunId = '';
      }
      return next;
    });
  };

  useEffect(() => {
    let cancelled = false;
    setFlow(emptyFlow);
    api.get<FlowContext>(`/api/v1/projects/${project.id}/workflow`)
      .then((persisted) => {
        if (!cancelled) setFlow((prev) => ({ ...prev, ...persisted }));
      })
      .catch(() => undefined);
    return () => { cancelled = true; };
  }, [project.id]);

  return (
    <div>
      <div className="card">
        <h2>{project.name} <span className="badge">{project.status}</span></h2>
        <p className="muted">{project.description} {project.windowNote ? `· Window: ${project.windowNote}` : ''}</p>
        <p className="muted">You are acting as <b>{role}</b>. Engineers prepare, reviewers decide and approve, auditors inspect.</p>
      </div>
      {error && <div className="error">{error}</div>}
      <div className="tabs">
        {TABS.map((t) => (
          <button key={t} className={tab === t ? 'active' : ''} onClick={() => setTab(t)}>{t}</button>
        ))}
      </div>
      {tab === 'Intake' && <Intake projectId={project.id} flow={flow} patch={patch} setError={setError} />}
      {tab === 'Mapping' && <Mappings projectId={project.id} flow={flow} patch={patch} setError={setError} />}
      {tab === 'Validation' && <Validation projectId={project.id} flow={flow} patch={patch} setError={setError} />}
      {tab === 'Exceptions' && <Exceptions flow={flow} setError={setError} />}
      {tab === 'Manifest' && <Manifest projectId={project.id} flow={flow} patch={patch} setError={setError} />}
      {tab === 'Rehearsal' && <Rehearsal flow={flow} patch={patch} setError={setError} />}
      {tab === 'UAT' && <Uat flow={flow} setError={setError} />}
      {tab === 'Approval' && <Approval flow={flow} patch={patch} setError={setError} />}
      {tab === 'Cutover' && <Cutover flow={flow} patch={patch} setError={setError} />}
      {tab === 'Audit' && <Audit projectId={project.id} setError={setError} />}
    </div>
  );
}
