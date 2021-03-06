CREATE DATABASE example1 OWNER user1;

\connect example1 ;

-- read model

CREATE TABLE projections (
    name VARCHAR(36) NOT NULL,
    last_uow INTEGER ,
    PRIMARY KEY (name)
);

CREATE TABLE customer_summary (
    id INTEGER NOT NULL,
    name VARCHAR(36) NOT NULL,
    is_active BOOLEAN NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE account_summary (
    id INTEGER NOT NULL,
    balance NUMERIC DEFAULT 0.00,
    PRIMARY KEY (id)
);


-- write model (in production, it could be a different database instance)

CREATE TABLE units_of_work (
      uow_id BIGSERIAL,
      uow_events JSONB NOT NULL,
      cmd_id UUID NOT NULL,
      cmd_name VARCHAR(36) NOT NULL,
      cmd_data JSONB NOT NULL,
      ar_name VARCHAR(36) NOT NULL,
      ar_id INTEGER NOT NULL,
      version INTEGER NOT NULL,
      inserted_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (ar_id, ar_name, version),
      UNIQUE (ar_id, uow_id),
      UNIQUE (ar_id, cmd_id)
    )
      PARTITION BY hash(ar_id) -- all related events within same partition
    ;

-- 3 partitions

CREATE TABLE units_of_work_0 PARTITION OF units_of_work
    FOR VALUES WITH (MODULUS 3, REMAINDER 0);
CREATE TABLE units_of_work_1 PARTITION OF units_of_work
    FOR VALUES WITH (MODULUS 3, REMAINDER 1);
CREATE TABLE units_of_work_2 PARTITION OF units_of_work
    FOR VALUES WITH (MODULUS 3, REMAINDER 2);

CREATE INDEX idx_cmd_id ON units_of_work (cmd_id);
CREATE INDEX idx_uow_id ON units_of_work (uow_id);
CREATE INDEX idx_ar ON units_of_work (ar_id, ar_name);

--  snapshots tables

CREATE TABLE customer_snapshots (
      ar_id INTEGER NOT NULL,
      version INTEGER,
      json_content JSONB NOT NULL,
      PRIMARY KEY (ar_id)
    );

CREATE TABLE account_snapshots (
      ar_id INTEGER NOT NULL,
      version INTEGER,
      json_content JSONB NOT NULL,
      PRIMARY KEY (ar_id)
    );

