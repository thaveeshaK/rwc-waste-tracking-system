-- Usernames remain immutable; never rename or merge historical account identities.
-- Reject legacy collisions explicitly rather than choosing an account to change.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM app_user GROUP BY lower(username) HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Case-insensitive duplicate usernames exist. Resolve the conflicting account identities before applying V19; no accounts have been renamed or merged.';
    END IF;
END $$;
CREATE UNIQUE INDEX app_user_username_case_insensitive ON app_user (lower(username));
