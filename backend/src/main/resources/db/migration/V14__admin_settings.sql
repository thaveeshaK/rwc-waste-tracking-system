CREATE TABLE application_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    organisation_name VARCHAR(100) NOT NULL CHECK (length(trim(organisation_name)) > 0),
    site_name VARCHAR(100) NOT NULL DEFAULT '',
    report_display_name VARCHAR(100) NOT NULL CHECK (length(trim(report_display_name)) > 0),
    display_unit VARCHAR(6) NOT NULL DEFAULT 'kg' CHECK (display_unit IN ('kg','tonnes')),
    report_period VARCHAR(32) NOT NULL DEFAULT 'CURRENT_MONTH' CHECK (report_period IN ('CURRENT_MONTH','CURRENT_FINANCIAL_YEAR')),
    financial_year_start_month SMALLINT NOT NULL DEFAULT 7 CHECK (financial_year_start_month BETWEEN 1 AND 12),
    whitegoods_kg_per_item NUMERIC(5,2) NOT NULL DEFAULT 48.00 CHECK (whitegoods_kg_per_item > 0),
    updated_by BIGINT REFERENCES app_user(id),
    updated_at TIMESTAMPTZ
);
INSERT INTO application_settings (id, organisation_name, report_display_name)
VALUES (1, 'Resource Work Cooperative', 'Resource Work Cooperative');

ALTER TABLE container_type ADD COLUMN settings_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE material_category ADD COLUMN settings_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE container_type ADD COLUMN settings_updated_by BIGINT REFERENCES app_user(id), ADD COLUMN settings_updated_at TIMESTAMPTZ;
ALTER TABLE material_category ADD COLUMN settings_updated_by BIGINT REFERENCES app_user(id), ADD COLUMN settings_updated_at TIMESTAMPTZ;
-- Names cannot be reused even when inactive: historical identities remain unambiguous.
CREATE UNIQUE INDEX container_type_name_folded ON container_type (lower(trim(name)));
CREATE UNIQUE INDEX material_category_name_folded ON material_category (lower(trim(name)));

ALTER TABLE crate_pallet_record ADD COLUMN container_name VARCHAR(100);
ALTER TABLE electrical_crate_pallet_record ADD COLUMN container_name VARCHAR(100);
UPDATE crate_pallet_record r SET container_name=c.name FROM container_type c WHERE c.id=r.container_type_id;
UPDATE electrical_crate_pallet_record r SET container_name=c.name FROM container_type c WHERE c.id=r.container_type_id;
-- Cover application native inserts as well as direct/import inserts without rewriting historical names.
CREATE FUNCTION snapshot_container_name() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.container_type_id <> OLD.container_type_id THEN
        SELECT name INTO NEW.container_name FROM container_type WHERE id=NEW.container_type_id;
    ELSE
        NEW.container_name := OLD.container_name;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER crate_pallet_container_name BEFORE INSERT OR UPDATE ON crate_pallet_record
FOR EACH ROW EXECUTE FUNCTION snapshot_container_name();
CREATE TRIGGER electrical_container_name BEFORE INSERT OR UPDATE ON electrical_crate_pallet_record
FOR EACH ROW EXECUTE FUNCTION snapshot_container_name();
ALTER TABLE crate_pallet_record ALTER COLUMN container_name SET NOT NULL;
ALTER TABLE electrical_crate_pallet_record ALTER COLUMN container_name SET NOT NULL;

ALTER TABLE whitegoods_record DROP CONSTRAINT whitegoods_record_kg_per_item_check;
ALTER TABLE whitegoods_record ADD CONSTRAINT whitegoods_positive_factor CHECK (kg_per_item > 0);
-- All INTEGER counts at the maximum NUMERIC(5,2) factor fit exactly.
ALTER TABLE whitegoods_record ALTER COLUMN estimated_weight TYPE NUMERIC(15,2);
