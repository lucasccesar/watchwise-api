ALTER TABLE picks_templates
    DROP CONSTRAINT ck_picks_templates_dates;

ALTER TABLE picks_templates
    ADD CONSTRAINT ck_picks_templates_dates CHECK (
        (eligibility_start_date IS NULL AND eligibility_end_date IS NULL)
        OR (eligibility_start_date IS NOT NULL AND eligibility_end_date IS NOT NULL
            AND eligibility_start_date <= eligibility_end_date)
    );
