-- V009: UAT, defects, approvals, cutover slot, audit (spec 8.1)
CREATE TABLE control.uat_cases (
    id               UUID PRIMARY KEY,
    rehearsal_run_id UUID NOT NULL REFERENCES control.runs(id),
    case_key         VARCHAR(64) NOT NULL,
    title            VARCHAR(300) NOT NULL,
    expected_result  TEXT NOT NULL,
    required         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uat_cases_unique UNIQUE (rehearsal_run_id, case_key)
);

CREATE TABLE control.uat_executions (
    id            UUID PRIMARY KEY,
    case_id       UUID NOT NULL REFERENCES control.uat_cases(id),
    executed_by   UUID NOT NULL REFERENCES control.users(id),
    result        VARCHAR(8) NOT NULL CHECK (result IN ('PASS','FAIL','BLOCKED')),
    actual_result TEXT,
    executed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.defects (
    id              UUID PRIMARY KEY,
    rehearsal_run_id UUID NOT NULL REFERENCES control.runs(id),
    uat_case_id     UUID REFERENCES control.uat_cases(id),
    severity        VARCHAR(16) NOT NULL CHECK (severity IN ('BLOCKER','CRITICAL','MAJOR','MINOR')),
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED','CLOSED')),
    description     TEXT NOT NULL,
    resolution_note TEXT,
    resolved_by     UUID REFERENCES control.users(id),
    resolved_at     TIMESTAMPTZ,
    created_by      UUID NOT NULL REFERENCES control.users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.approvals (
    id                        UUID PRIMARY KEY,
    manifest_id               UUID NOT NULL REFERENCES control.manifests(id),
    rehearsal_run_id          UUID NOT NULL REFERENCES control.runs(id),
    reconciliation_report_id  UUID NOT NULL REFERENCES control.reconciliation_reports(id),
    uat_evidence_hash         CHAR(64) NOT NULL,
    decision                  VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVED','REJECTED')),
    comment                   TEXT,
    reviewer_id               UUID NOT NULL REFERENCES control.users(id),
    signed_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.target_cutover_slot (
    slot_id        INTEGER PRIMARY KEY CHECK (slot_id = 1),
    active_run_id  UUID REFERENCES control.runs(id),
    verified_run_id UUID REFERENCES control.runs(id),
    status         VARCHAR(16) NOT NULL DEFAULT 'FREE' CHECK (status IN ('FREE','RESERVED','VERIFIED')),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO control.target_cutover_slot (slot_id) VALUES (1);

CREATE TABLE control.audit_events (
    id          UUID PRIMARY KEY,
    project_id  UUID REFERENCES control.projects(id),
    actor_id    UUID REFERENCES control.users(id),
    action      VARCHAR(64) NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id   UUID,
    metadata_json JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_events_project_idx ON control.audit_events (project_id, occurred_at DESC);
