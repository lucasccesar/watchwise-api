ALTER TABLE tracked_person_credits
    DROP CONSTRAINT uq_tracked_person_credits_state_credit;

ALTER TABLE tracked_person_credits
    ADD CONSTRAINT uq_tracked_person_credits_state_type_credit
        UNIQUE (tracked_person_state_id, credit_type, credit_tmdb_id);
