-- Exact approved legacy names only; unknown/custom names safely retain all weight as OTHER.
ALTER TABLE container_type ADD COLUMN summary_group VARCHAR(6) NOT NULL DEFAULT 'OTHER'
    CHECK (summary_group IN ('CRATE', 'PALLET', 'OTHER'));
UPDATE container_type SET summary_group = CASE
    WHEN name IN ('Apple Crate', 'Stillage Cage') THEN 'CRATE'
    WHEN name IN ('Plastic Pallet', 'Pine Pallet', 'Hardwood Pallet') THEN 'PALLET'
    ELSE 'OTHER' END;
ALTER TABLE crate_pallet_record ADD COLUMN summary_group_snapshot VARCHAR(6);
UPDATE crate_pallet_record SET summary_group_snapshot = CASE
    WHEN container_name IN ('Apple Crate', 'Stillage Cage') THEN 'CRATE'
    WHEN container_name IN ('Plastic Pallet', 'Pine Pallet', 'Hardwood Pallet') THEN 'PALLET'
    ELSE 'OTHER' END;
ALTER TABLE crate_pallet_record ALTER COLUMN summary_group_snapshot SET NOT NULL;
ALTER TABLE crate_pallet_record ADD CHECK (summary_group_snapshot IN ('CRATE', 'PALLET', 'OTHER'));
-- Match historical name snapshot behaviour: corrections retain the group unless the container changes.
CREATE FUNCTION snapshot_container_summary_group() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.container_type_id <> OLD.container_type_id THEN
        SELECT summary_group INTO NEW.summary_group_snapshot FROM container_type WHERE id=NEW.container_type_id;
    ELSE
        NEW.summary_group_snapshot := OLD.summary_group_snapshot;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER crate_pallet_summary_group BEFORE INSERT OR UPDATE ON crate_pallet_record
FOR EACH ROW EXECUTE FUNCTION snapshot_container_summary_group();
