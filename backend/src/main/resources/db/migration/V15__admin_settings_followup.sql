-- V14 is immutable. CURRENT_FINANCIAL_YEAR needs more than its original 20 characters.
ALTER TABLE application_settings
    ALTER COLUMN report_period TYPE VARCHAR(32);
