ALTER TABLE user_lists ADD COLUMN rank INTEGER;
ALTER TABLE user_lists ADD CONSTRAINT uq_user_lists_user_id_rank UNIQUE (user_id, rank);
