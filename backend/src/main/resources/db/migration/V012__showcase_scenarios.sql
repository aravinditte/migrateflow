-- V012: public showcase scenario registry (Automated Showcase spec 5.1).
-- A registry row is a POINTER to real workflow artifacts, never a source
-- of counts, statuses or totals: every displayed figure is read from the
-- domain tables the row references, and lineage is re-verified on read.
CREATE TABLE control.showcase_scenarios (
    id                  UUID PRIMARY KEY,
    slug                VARCHAR(64) NOT NULL UNIQUE,
    project_id          UUID REFERENCES control.projects(id),
    bundle_id           UUID REFERENCES control.dataset_bundles(id),
    mapping_revision_id UUID REFERENCES control.mapping_revisions(id),
    rule_set_revision_id UUID REFERENCES control.rule_set_revisions(id),
    snapshot_id         UUID REFERENCES control.validation_snapshots(id),
    manifest_id         UUID REFERENCES control.manifests(id),
    rehearsal_run_id    UUID REFERENCES control.runs(id),
    approval_id         UUID REFERENCES control.approvals(id),
    cutover_run_id      UUID REFERENCES control.runs(id),
    setup_state         VARCHAR(24) NOT NULL DEFAULT 'PREPARING'
                        CHECK (setup_state IN ('PREPARING','READY','FAILED','STALE')),
    setup_step          VARCHAR(48),
    bootstrap_version   VARCHAR(64) NOT NULL,
    failure_message     TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at         TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
