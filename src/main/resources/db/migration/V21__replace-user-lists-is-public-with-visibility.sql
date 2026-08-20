ALTER TABLE user_lists ADD COLUMN visibility VARCHAR(20);

UPDATE user_lists SET visibility = CASE WHEN is_public THEN 'PUBLIC' ELSE 'PRIVATE' END;

ALTER TABLE user_lists ALTER COLUMN visibility SET NOT NULL;
ALTER TABLE user_lists ALTER COLUMN visibility SET DEFAULT 'PUBLIC';
ALTER TABLE user_lists ADD CONSTRAINT ck_user_lists_visibility CHECK (visibility IN ('PUBLIC', 'FOLLOWERS', 'PRIVATE'));

ALTER TABLE user_lists DROP COLUMN is_public;