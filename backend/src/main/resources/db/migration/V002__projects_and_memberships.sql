-- V002: projects and memberships
CREATE TABLE control.projects (
    id          UUID PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    window_note TEXT,
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
    created_by  UUID NOT NULL REFERENCES control.users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE control.project_memberships (
    id         UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES control.projects(id),
    user_id    UUID NOT NULL REFERENCES control.users(id),
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT memberships_unique UNIQUE (project_id, user_id)
);
