-- Reuse existing account identities and preserve every historical FK.
DROP INDEX app_user_single_staff;
ALTER TABLE app_user
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD COLUMN created_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ,
    ADD COLUMN created_by BIGINT REFERENCES app_user(id),
    ADD COLUMN updated_by BIGINT REFERENCES app_user(id);
-- Historical creation times/actors were never recorded; keep them NULL, rather than invent dates.
