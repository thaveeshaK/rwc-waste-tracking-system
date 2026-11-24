-- Existing loads were validated as complete. Keep the default for older clients/inserts.
ALTER TABLE crate_pallet_record ADD COLUMN status VARCHAR(10);
UPDATE crate_pallet_record SET status = 'COMPLETED';
ALTER TABLE crate_pallet_record ALTER COLUMN status SET DEFAULT 'COMPLETED';
ALTER TABLE crate_pallet_record ALTER COLUMN status SET NOT NULL;
ALTER TABLE crate_pallet_record ADD CONSTRAINT crate_pallet_record_status_check
    CHECK (status IN ('INCOMPLETE', 'COMPLETED'));

ALTER TABLE electrical_crate_pallet_record ADD COLUMN status VARCHAR(10);
UPDATE electrical_crate_pallet_record SET status = 'COMPLETED';
ALTER TABLE electrical_crate_pallet_record ALTER COLUMN status SET DEFAULT 'COMPLETED';
ALTER TABLE electrical_crate_pallet_record ALTER COLUMN status SET NOT NULL;
ALTER TABLE electrical_crate_pallet_record ADD CONSTRAINT electrical_crate_pallet_record_status_check
    CHECK (status IN ('INCOMPLETE', 'COMPLETED'));
