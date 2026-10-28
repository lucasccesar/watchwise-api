CREATE SEQUENCE refresh_observation_generation_seq
    AS BIGINT
    START WITH 1
    INCREMENT BY 1
    NO CYCLE;

ALTER TABLE tracked_content_states
    ADD COLUMN refresh_generation BIGINT NOT NULL DEFAULT 0;

ALTER TABLE series_progress_metadata
    ADD COLUMN refresh_generation BIGINT NOT NULL DEFAULT 0;

ALTER TABLE contents
    ADD COLUMN refresh_generation BIGINT NOT NULL DEFAULT 0;

ALTER TABLE content_release_date_snapshots
    ADD COLUMN refresh_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN refresh_claim_id UUID;
