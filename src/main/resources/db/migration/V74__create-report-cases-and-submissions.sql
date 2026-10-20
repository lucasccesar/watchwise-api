CREATE TABLE report_cases (
    id UUID PRIMARY KEY,
    target_type VARCHAR(30) NOT NULL,
    target_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    internal_notes VARCHAR(2000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    closed_at TIMESTAMP,
    CONSTRAINT ck_report_cases_target_type CHECK (target_type IN (
        'USER', 'COMMENT', 'REVIEW', 'USER_LIST', 'PICK', 'PICKS_TEMPLATE', 'DROPPED_ENTRY')),
    CONSTRAINT ck_report_cases_status CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED', 'DISMISSED'))
);

CREATE UNIQUE INDEX uq_report_cases_active_target
    ON report_cases (target_type, target_id)
    WHERE status IN ('OPEN', 'IN_REVIEW');

CREATE INDEX idx_report_cases_status_created_at
    ON report_cases (status, created_at ASC, id ASC);

CREATE TABLE report_submissions (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES report_cases(id) ON DELETE CASCADE,
    reporter_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason VARCHAR(40) NOT NULL,
    details VARCHAR(280),
    target_snapshot TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_report_submissions_case_reporter UNIQUE (case_id, reporter_id),
    CONSTRAINT ck_report_submissions_reason CHECK (reason IN (
        'SPAM_OR_MISLEADING', 'HARASSMENT_OR_BULLYING', 'HATE_SPEECH',
        'UNMARKED_SPOILER', 'SEXUAL_OR_VIOLENT_CONTENT', 'OTHER'))
);

CREATE INDEX idx_report_submissions_case_id ON report_submissions (case_id);
