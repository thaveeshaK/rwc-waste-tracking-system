-- Use exact historical names, not current container configuration, for the one-time backfill.
ALTER TABLE electrical_crate_pallet_record ADD COLUMN summary_group_snapshot VARCHAR(6);
UPDATE electrical_crate_pallet_record SET summary_group_snapshot = CASE
    WHEN container_name = 'Stillage Cage' THEN 'CRATE'
    WHEN container_name IN ('Plastic Pallet', 'Pine Pallet', 'Hardwood Pallet') THEN 'PALLET'
    ELSE 'OTHER' END;
ALTER TABLE electrical_crate_pallet_record ALTER COLUMN summary_group_snapshot SET NOT NULL;
ALTER TABLE electrical_crate_pallet_record ADD CHECK (summary_group_snapshot IN ('CRATE', 'PALLET', 'OTHER'));
-- V20's function preserves the original group for same-container corrections.
CREATE TRIGGER electrical_crate_pallet_summary_group BEFORE INSERT OR UPDATE ON electrical_crate_pallet_record
FOR EACH ROW EXECUTE FUNCTION snapshot_container_summary_group();
