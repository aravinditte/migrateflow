-- V005: staging - raw input rows and typed validated rows (spec section 8.2)
CREATE TABLE stage.customer_input_rows (
    id                UUID PRIMARY KEY,
    file_id           UUID NOT NULL REFERENCES control.source_files(id),
    data_row_number   INTEGER NOT NULL CHECK (data_row_number > 0),
    physical_line_start INTEGER NOT NULL CHECK (physical_line_start > 0),
    raw_json          JSONB NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT customer_input_rows_unique UNIQUE (file_id, data_row_number)
);

CREATE TABLE stage.order_input_rows (
    id                UUID PRIMARY KEY,
    file_id           UUID NOT NULL REFERENCES control.source_files(id),
    data_row_number   INTEGER NOT NULL CHECK (data_row_number > 0),
    physical_line_start INTEGER NOT NULL CHECK (physical_line_start > 0),
    raw_json          JSONB NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT order_input_rows_unique UNIQUE (file_id, data_row_number)
);

CREATE TABLE stage.customer_validated_rows (
    id                    UUID PRIMARY KEY,
    snapshot_id           UUID NOT NULL REFERENCES control.validation_snapshots(id),
    input_row_id          UUID NOT NULL,
    external_customer_id  VARCHAR(32),
    full_name             VARCHAR(200),
    email                 VARCHAR(254),
    joined_on             DATE,
    is_valid              BOOLEAN NOT NULL,
    normalized_hash       CHAR(64) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT customer_validated_unique UNIQUE (snapshot_id, input_row_id)
);

CREATE TABLE stage.order_validated_rows (
    id                    UUID PRIMARY KEY,
    snapshot_id           UUID NOT NULL REFERENCES control.validation_snapshots(id),
    input_row_id          UUID NOT NULL,
    external_order_id     VARCHAR(32),
    external_customer_id  VARCHAR(32),
    order_date            DATE,
    amount_yen            BIGINT,
    status                VARCHAR(16),
    is_valid              BOOLEAN NOT NULL,
    normalized_hash       CHAR(64) NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT order_validated_unique UNIQUE (snapshot_id, input_row_id)
);
