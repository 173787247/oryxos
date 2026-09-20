-- V23 versioned asset source (#473 / 049): content versions + shared active pointer (PostgreSQL).

CREATE TABLE IF NOT EXISTS workspace_asset_versions (
    id BIGSERIAL PRIMARY KEY,
    kind VARCHAR(32) NOT NULL,
    asset_id VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (kind, asset_id, version)
);

CREATE TABLE IF NOT EXISTS workspace_asset_active (
    kind VARCHAR(32) NOT NULL,
    asset_id VARCHAR(128) NOT NULL,
    active_version BIGINT NOT NULL,
    previous_version BIGINT,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (kind, asset_id)
);
