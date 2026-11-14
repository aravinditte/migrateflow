-- V003: dataset bundles and source files
CREATE TABLE control.dataset_bundles (
    id          UUID PRIMARY KEY,
    project_id  UUID NOT NULL REFERENCES control.projects(id),
    exported_at TIMESTAMPTZ,
    status      VARCHAR(16) NOT NULL DEFAULT 'UPLOADED' CHECK (status IN ('UPLOADED','PROFILED','FAILED')),
    uploaded_by UUID NOT NULL REFERENCES control.users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.source_files (
    id          UUID PRIMARY KEY,
    bundle_id   UUID NOT NULL REFERENCES control.dataset_bundles(id),
    kind        VARCHAR(16) NOT NULL CHECK (kind IN ('CUSTOMERS','ORDERS')),
    filename    VARCHAR(255) NOT NULL,
    storage_key VARCHAR(128) NOT NULL UNIQUE,
    bytes       BIGINT NOT NULL CHECK (bytes >= 0),
    sha256      CHAR(64) NOT NULL,
    row_count   INTEGER NOT NULL DEFAULT 0 CHECK (row_count >= 0),
    uploaded_by UUID NOT NULL REFERENCES control.users(id),
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT source_files_bundle_kind UNIQUE (bundle_id, kind)
);
