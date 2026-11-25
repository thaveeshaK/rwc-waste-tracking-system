-- Existing collectable_record.tare_weight already holds the applied historical snapshot.
ALTER TABLE application_settings ADD COLUMN collectables_tub_tare NUMERIC(10,2) NOT NULL DEFAULT 2.00
    CHECK (collectables_tub_tare > 0);
ALTER TABLE collectable_record DROP CONSTRAINT collectable_record_gross_weight_check;
ALTER TABLE collectable_record DROP CONSTRAINT collectable_record_tare_weight_check;
ALTER TABLE collectable_record ADD CONSTRAINT collectable_record_gross_weight_check CHECK (gross_weight > 0);
ALTER TABLE collectable_record ADD CONSTRAINT collectable_record_tare_weight_check CHECK (tare_weight > 0);
