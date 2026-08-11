ALTER TABLE users DROP CONSTRAINT uq_users_username;
CREATE UNIQUE INDEX uq_users_username ON users (LOWER(username));