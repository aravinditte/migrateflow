-- MigrateFlow V001: schemas and users (spec section 8.1)
CREATE SCHEMA IF NOT EXISTS control;
CREATE SCHEMA IF NOT EXISTS stage;
CREATE SCHEMA IF NOT EXISTS rehearsal;
CREATE SCHEMA IF NOT EXISTS target;

CREATE TABLE control.users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(120) NOT NULL,
    role          VARCHAR(16)  NOT NULL CHECK (role IN ('ENGINEER','REVIEWER','AUDITOR','ADMIN')),
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT users_email_lower_unique UNIQUE (email),
    CONSTRAINT users_email_is_lower CHECK (email = lower(email))
);
