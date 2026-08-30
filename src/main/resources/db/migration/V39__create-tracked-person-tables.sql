-- V39__create-tracked-person-tables.sql
CREATE TABLE tracked_person_states (
    id UUID PRIMARY KEY,
    person_tmdb_id VARCHAR(20) NOT NULL,
    last_checked_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_tracked_person_states_person_tmdb_id UNIQUE (person_tmdb_id)
);

CREATE TABLE tracked_person_credits (
    id UUID PRIMARY KEY,
    tracked_person_state_id UUID NOT NULL REFERENCES tracked_person_states(id) ON DELETE CASCADE,
    credit_tmdb_id VARCHAR(20) NOT NULL,
    credit_type VARCHAR(6) NOT NULL,
    CONSTRAINT uq_tracked_person_credits_state_credit UNIQUE (tracked_person_state_id, credit_tmdb_id)
);

CREATE INDEX idx_tracked_person_credits_state_id ON tracked_person_credits(tracked_person_state_id);
