-- V011: Enforce at most one active FROZEN manifest per project (spec 5 / MF-009)
CREATE UNIQUE INDEX manifests_project_frozen_unique
    ON control.manifests (project_id) WHERE status = 'FROZEN';
