-- V22 Flow step wait expiry (047 / #469): human/approval timeout.

ALTER TABLE flow_steps ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
