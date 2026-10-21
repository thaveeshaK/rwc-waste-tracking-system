ALTER TABLE crate_pallet_record
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);
