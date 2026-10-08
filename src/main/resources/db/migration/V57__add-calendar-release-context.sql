ALTER TABLE calendar_schedule_snapshots
    ADD COLUMN release_time TIME,
    ADD COLUMN network VARCHAR(200);
