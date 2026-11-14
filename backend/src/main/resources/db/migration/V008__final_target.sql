-- V008: simulated final target (spec 8.3). Insert-only business identity.
CREATE TABLE target.customers (
    id                   UUID PRIMARY KEY,
    external_customer_id VARCHAR(32) NOT NULL UNIQUE,
    full_name            VARCHAR(200) NOT NULL,
    email                VARCHAR(254),
    joined_on            DATE,
    origin_run_id        UUID NOT NULL REFERENCES control.runs(id),
    origin_manifest_id   UUID NOT NULL REFERENCES control.manifests(id),
    source_file_id       UUID NOT NULL REFERENCES control.source_files(id),
    source_row_number    INTEGER NOT NULL CHECK (source_row_number > 0),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (length(trim(full_name)) > 0)
);

CREATE TABLE target.orders (
    id                 UUID PRIMARY KEY,
    external_order_id  VARCHAR(32) NOT NULL UNIQUE,
    customer_id        UUID NOT NULL REFERENCES target.customers(id),
    order_date         DATE NOT NULL,
    amount_yen         BIGINT NOT NULL CHECK (amount_yen >= 0),
    status             VARCHAR(16) NOT NULL CHECK (status IN ('NEW','PAID','SHIPPED','CANCELLED')),
    origin_run_id      UUID NOT NULL REFERENCES control.runs(id),
    origin_manifest_id UUID NOT NULL REFERENCES control.manifests(id),
    source_file_id     UUID NOT NULL REFERENCES control.source_files(id),
    source_row_number  INTEGER NOT NULL CHECK (source_row_number > 0),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
