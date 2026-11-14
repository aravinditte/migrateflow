-- V007: rehearsal target, run-scoped business keys and composite FK (spec 7.4)
CREATE TABLE rehearsal.customers (
    id                   UUID NOT NULL,
    rehearsal_run_id     UUID NOT NULL REFERENCES control.runs(id),
    external_customer_id VARCHAR(32) NOT NULL,
    full_name            VARCHAR(200) NOT NULL,
    email                VARCHAR(254),
    joined_on            DATE,
    origin_run_id        UUID NOT NULL REFERENCES control.runs(id),
    origin_manifest_id   UUID NOT NULL REFERENCES control.manifests(id),
    source_file_id       UUID NOT NULL REFERENCES control.source_files(id),
    source_row_number    INTEGER NOT NULL CHECK (source_row_number > 0),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (length(trim(full_name)) > 0),
    CONSTRAINT rehearsal_customers_pk PRIMARY KEY (rehearsal_run_id, id),
    CONSTRAINT rehearsal_customers_bizkey UNIQUE (rehearsal_run_id, external_customer_id)
);

CREATE TABLE rehearsal.orders (
    id                 UUID NOT NULL,
    rehearsal_run_id   UUID NOT NULL REFERENCES control.runs(id),
    external_order_id  VARCHAR(32) NOT NULL,
    customer_id        UUID NOT NULL,
    order_date         DATE NOT NULL,
    amount_yen         BIGINT NOT NULL CHECK (amount_yen >= 0),
    status             VARCHAR(16) NOT NULL CHECK (status IN ('NEW','PAID','SHIPPED','CANCELLED')),
    origin_run_id      UUID NOT NULL REFERENCES control.runs(id),
    origin_manifest_id UUID NOT NULL REFERENCES control.manifests(id),
    source_file_id     UUID NOT NULL REFERENCES control.source_files(id),
    source_row_number  INTEGER NOT NULL CHECK (source_row_number > 0),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT rehearsal_orders_pk PRIMARY KEY (rehearsal_run_id, id),
    CONSTRAINT rehearsal_orders_bizkey UNIQUE (rehearsal_run_id, external_order_id),
    CONSTRAINT rehearsal_orders_customer_fk
        FOREIGN KEY (rehearsal_run_id, customer_id)
        REFERENCES rehearsal.customers (rehearsal_run_id, id)
);
