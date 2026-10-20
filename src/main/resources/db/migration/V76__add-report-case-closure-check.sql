ALTER TABLE report_cases
    ADD CONSTRAINT ck_report_cases_closed_at_by_status CHECK (
        (status IN ('OPEN', 'IN_REVIEW') AND closed_at IS NULL)
        OR (status IN ('RESOLVED', 'DISMISSED') AND closed_at IS NOT NULL)
    );
