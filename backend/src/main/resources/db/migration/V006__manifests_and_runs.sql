-- V006: manifests, runs, applied-rows ledger, reconciliation reports
CREATE TABLE control.manifests (
    id                   UUID PRIMARY KEY,
    project_id           UUID NOT NULL REFERENCES control.projects(id),
    bundle_id            UUID NOT NULL REFERENCES control.dataset_bundles(id),
    mapping_revision_id  UUID NOT NULL REFERENCES control.mapping_revisions(id),
    rule_set_revision_id UUID NOT NULL REFERENCES control.rule_set_revisions(id),
    snapshot_id          UUID NOT NULL REFERENCES control.validation_snapshots(id),
    exclusion_hash       CHAR(64) NOT NULL,
    target_contract_hash CHAR(64) NOT NULL,
    manifest_hash        CHAR(64) NOT NULL,
    status               VARCHAR(16) NOT NULL DEFAULT 'FROZEN' CHECK (status IN ('FROZEN','SUPERSEDED')),
    created_by           UUID NOT NULL REFERENCES control.users(id),
    superseded_at        TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT manifests_hash_unique UNIQUE (project_id, manifest_hash)
);

CREATE TABLE control.runs (
    id                     UUID PRIMARY KEY,
    project_id             UUID NOT NULL REFERENCES control.projects(id),
    manifest_id            UUID NOT NULL REFERENCES control.manifests(id),
    kind                   VARCHAR(16) NOT NULL CHECK (kind IN ('REHEARSAL','CUTOVER')),
    status                 VARCHAR(24) NOT NULL DEFAULT 'QUEUED'
                           CHECK (status IN ('QUEUED','RUNNING','FAILED_RESTARTABLE','FAILED_TERMINAL',
                                             'BATCH_COMPLETED','VERIFICATION_FAILED','VERIFIED')),
    requested_by           UUID NOT NULL REFERENCES control.users(id),
    approval_id            UUID,
    request_key            VARCHAR(128),
    batch_job_instance_id  BIGINT,
    batch_job_execution_id BIGINT,
    processed_rows         INTEGER NOT NULL DEFAULT 0,
    expected_accepted_rows INTEGER NOT NULL DEFAULT 0,
    current_step           VARCHAR(64),
    error_message          TEXT,
    started_at             TIMESTAMPTZ,
    finished_at            TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX runs_request_key_unique
    ON control.runs (project_id, request_key) WHERE request_key IS NOT NULL;

CREATE TABLE control.applied_rows (
    id               UUID PRIMARY KEY,
    run_id           UUID NOT NULL REFERENCES control.runs(id),
    kind             VARCHAR(16) NOT NULL CHECK (kind IN ('CUSTOMERS','ORDERS')),
    source_row_id    UUID NOT NULL,
    target_record_id UUID NOT NULL,
    normalized_hash  CHAR(64) NOT NULL,
    applied_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT applied_rows_source_unique UNIQUE (run_id, kind, source_row_id),
    CONSTRAINT applied_rows_target_unique UNIQUE (run_id, kind, target_record_id)
);

CREATE TABLE control.reconciliation_reports (
    id            UUID PRIMARY KEY,
    run_id        UUID NOT NULL REFERENCES control.runs(id),
    status        VARCHAR(8) NOT NULL CHECK (status IN ('PASS','FAIL')),
    summary_json  JSONB NOT NULL,
    evidence_hash CHAR(64) NOT NULL,
    completed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX reconciliation_reports_run_idx ON control.reconciliation_reports (run_id, completed_at DESC);
