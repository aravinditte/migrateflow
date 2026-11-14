// Shared state for the project workspace: the identifiers produced by each
// workflow stage, handed from tab to tab (spec 11 single-project flow).

export interface FlowContext {
  bundleId: string;
  mappingRevisionId: string;
  ruleSetRevisionId: string;
  snapshotId: string;
  manifestId: string;
  manifestHash: string;
  rehearsalRunId: string;
  cutoverRunId: string;
  approvalId: string;
}

export const emptyFlow: FlowContext = {
  bundleId: '', mappingRevisionId: '', ruleSetRevisionId: '', snapshotId: '',
  manifestId: '', manifestHash: '', rehearsalRunId: '', cutoverRunId: '', approvalId: '',
};

export interface Project { id: string; name: string; description: string; status: string; windowNote: string }

export type SectionProps = {
  projectId: string;
  flow: FlowContext;
  patch: (p: Partial<FlowContext>) => void;
  setError: (e: string) => void;
};

export function message(e: unknown): string {
  return e instanceof Error ? e.message : 'Request failed';
}
