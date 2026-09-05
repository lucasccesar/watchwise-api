ALTER TABLE watch_companions DROP CONSTRAINT fk_watch_companions_user;

ALTER TABLE watch_companions
    ADD CONSTRAINT fk_watch_companions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;
