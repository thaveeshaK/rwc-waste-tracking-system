CREATE TABLE material_category_applicability (
    context VARCHAR(32) NOT NULL,
    material_category_id BIGINT NOT NULL REFERENCES material_category(id),
    active BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (context, material_category_id)
);

-- Explicitly preserve the original Crate/Pallet set before adding Salvage categories.
INSERT INTO material_category_applicability (context, material_category_id)
SELECT 'CRATE_PALLET', id FROM material_category WHERE name IN (
    'Iron and Steel', 'Aluminium', 'Other Non-Ferrous Metals', 'Mixed Metals',
    'Textiles', 'Mattresses', 'Timber', 'Bricks, Blocks and Pavers',
    'Building and Demolition Material', 'Paper, Cardboard and Books',
    'Plastics', 'Glass', 'Mixed Materials'
);

INSERT INTO material_category (name) VALUES
    ('E-waste'), ('Lead Acid Batteries'), ('Whitegoods / Large Appliances')
ON CONFLICT (name) DO NOTHING;

INSERT INTO material_category_applicability (context, material_category_id)
SELECT 'SALVAGE_UP', id FROM material_category WHERE name IN (
    'Iron and Steel', 'Timber', 'Textiles', 'Plastics', 'Paper, Cardboard and Books',
    'Mattresses', 'E-waste', 'Glass', 'Mixed Materials'
);

INSERT INTO material_category_applicability (context, material_category_id)
SELECT 'SALVAGE_DOWN', id FROM material_category WHERE name IN (
    'Iron and Steel', 'Aluminium', 'Other Non-Ferrous Metals', 'Mixed Metals',
    'E-waste', 'Lead Acid Batteries', 'Timber', 'Bricks, Blocks and Pavers',
    'Building and Demolition Material', 'Whitegoods / Large Appliances',
    'Paper, Cardboard and Books', 'Plastics', 'Glass', 'Mixed Materials'
);
