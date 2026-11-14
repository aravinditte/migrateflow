-- V004: mapping revisions, rule-set revisions, validation snapshots, issues, exclusions
CREATE TABLE control.mapping_revisions (
    id              UUID PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES control.projects(id),
    revision_no     INTEGER NOT NULL CHECK (revision_no > 0),
    definition_json JSONB NOT NULL,
    definition_hash CHAR(64) NOT NULL,
    published_by    UUID NOT NULL REFERENCES control.users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT mapping_revisions_unique UNIQUE (project_id, revision_no)
);

CREATE TABLE control.rule_set_revisions (
    id          UUID PRIMARY KEY,
    project_id  UUID NOT NULL REFERENCES control.projects(id),
    revision_no INTEGER NOT NULL CHECK (revision_no > 0),
    rule_json   JSONB NOT NULL,
    rule_hash   CHAR(64) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rule_set_revisions_unique UNIQUE (project_id, revision_no)
);

CREATE TABLE control.validation_snapshots (
    id                   UUID PRIMARY KEY,
    project_id           UUID NOT NULL REFERENCES control.projects(id),
    bundle_id            UUID NOT NULL REFERENCES control.dataset_bundles(id),
    mapping_revision_id  UUID NOT NULL REFERENCES control.mapping_revisions(id),
    rule_set_revision_id UUID NOT NULL REFERENCES control.rule_set_revisions(id),
    status               VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
                         CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED')),
    source_counts_json   JSONB,
    created_by           UUID NOT NULL REFERENCES control.users(id),
    validated_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.validation_issues (
    id            UUID PRIMARY KEY,
    snapshot_id   UUID NOT NULL REFERENCES control.validation_snapshots(id),
    kind          VARCHAR(16) NOT NULL CHECK (kind IN ('CUSTOMERS','ORDERS')),
    source_row_id UUID NOT NULL,
    code          VARCHAR(32) NOT NULL,
    severity      VARCHAR(8)  NOT NULL DEFAULT 'ERROR' CHECK (severity IN ('ERROR','WARNING')),
    field         VARCHAR(64),
    message       TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX validation_issues_snapshot_idx ON control.validation_issues (snapshot_id, kind, code);
CREATE INDEX validation_issues_row_idx ON control.validation_issues (snapshot_id, source_row_id);

CREATE TABLE control.exclusion_decisions (
    id            UUID PRIMARY KEY,
    snapshot_id   UUID NOT NULL REFERENCES control.validation_snapshots(id),
    kind          VARCHAR(16) NOT NULL CHECK (kind IN ('CUSTOMERS','ORDERS')),
    source_row_id UUID NOT NULL,
    decision      VARCHAR(16) NOT NULL CHECK (decision IN ('EXCLUDE','KEEP')),
    reason        TEXT NOT NULL,
    reviewer_id   UUID NOT NULL REFERENCES control.users(id),
    decided_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX exclusion_decisions_snapshot_idx
    ON control.exclusion_decisions (snapshot_id, kind, source_row_id, decided_at DESC);
