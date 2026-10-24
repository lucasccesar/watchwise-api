CREATE TEMP TABLE b9_legacy_diary_entry_ids ON COMMIT DROP AS
SELECT diary.id, diary.user_id, diary.content_id
FROM diary_entries diary
JOIN contents content ON content.id = diary.content_id
WHERE content.type IN ('SERIES', 'SEASON');

CREATE TEMP TABLE b9_legacy_content_review_ids ON COMMIT DROP AS
SELECT review.id, review.user_id, review.content_id
FROM content_reviews review
WHERE review.publication_kind = 'LEGACY_ACTIVITY';

UPDATE content_reviews
SET context_kind = 'UNKNOWN'
WHERE publication_kind = 'WRITTEN_REVIEW'
  AND context_kind = 'LEGACY';

CREATE TEMP TABLE b9_legacy_comment_ids ON COMMIT DROP AS
WITH RECURSIVE affected_comments(id) AS (
    SELECT comment.id
    FROM comments comment
    WHERE comment.diary_entry_id IN (SELECT id FROM b9_legacy_diary_entry_ids)
       OR comment.content_review_id IN (SELECT id FROM b9_legacy_content_review_ids)
    UNION
    SELECT reply.id
    FROM comments reply
    JOIN affected_comments parent ON parent.id = reply.parent_comment_id
)
SELECT id FROM affected_comments;

CREATE TEMP TABLE b9_legacy_imported_watch_assertion_ids ON COMMIT DROP AS
SELECT assertion.id, assertion.user_id
FROM imported_watch_assertions assertion
JOIN contents content ON content.id = assertion.content_id
WHERE content.type IN ('SERIES', 'SEASON');

CREATE TEMP TABLE b9_legacy_report_case_ids ON COMMIT DROP AS
SELECT report.id
FROM report_cases report
WHERE (report.target_type = 'REVIEW'
       AND report.target_id IN (SELECT id FROM b9_legacy_diary_entry_ids))
   OR (report.target_type = 'COMMENT'
       AND report.target_id IN (SELECT id FROM b9_legacy_comment_ids))
   OR (report.target_type = 'CONTENT_REVIEW'
       AND report.target_id IN (SELECT id FROM b9_legacy_content_review_ids));

DELETE FROM notifications notification
WHERE (notification.target_type = 'DIARY_ENTRY'
       AND notification.target_id IN (SELECT id FROM b9_legacy_diary_entry_ids))
   OR (notification.target_type = 'COMMENT'
       AND notification.target_id IN (SELECT id FROM b9_legacy_comment_ids))
   OR (notification.target_type = 'CONTENT_REVIEW'
       AND notification.target_id IN (SELECT id FROM b9_legacy_content_review_ids))
   OR (notification.target_type = 'REPORT_CASE'
       AND notification.target_id IN (SELECT id FROM b9_legacy_report_case_ids));

DELETE FROM report_cases
WHERE id IN (SELECT id FROM b9_legacy_report_case_ids);

DELETE FROM external_import_links link
WHERE (link.target_type = 'DIARY_ENTRY'
       AND link.target_id IN (SELECT id FROM b9_legacy_diary_entry_ids))
   OR (link.target_type = 'CONTENT_REVIEW'
       AND link.target_id IN (SELECT id FROM b9_legacy_content_review_ids))
   OR (link.target_type = 'IMPORTED_WATCH_ASSERTION'
       AND link.target_id IN (SELECT id FROM b9_legacy_imported_watch_assertion_ids));

DELETE FROM comments
WHERE id IN (SELECT id FROM b9_legacy_comment_ids);

DELETE FROM content_reviews
WHERE id IN (SELECT id FROM b9_legacy_content_review_ids);

DELETE FROM diary_completion_projections;
DELETE FROM user_series_history_states;
DELETE FROM user_action_receipts;

DELETE FROM diary_entries
WHERE id IN (SELECT id FROM b9_legacy_diary_entry_ids);

DELETE FROM imported_watch_assertions
WHERE id IN (SELECT id FROM b9_legacy_imported_watch_assertion_ids);

DROP TABLE legacy_diary_migration_maps;
DROP TABLE legacy_diary_snapshots;

ALTER TABLE diary_entries DROP COLUMN auto_generated;

ALTER TABLE content_reviews DROP CONSTRAINT content_reviews_publication_kind_check;
ALTER TABLE content_reviews ADD CONSTRAINT ck_content_reviews_publication_kind
    CHECK (publication_kind = 'WRITTEN_REVIEW');

ALTER TABLE content_reviews DROP CONSTRAINT content_reviews_context_kind_check;
ALTER TABLE content_reviews ADD CONSTRAINT ck_content_reviews_context_kind
    CHECK (context_kind IN ('UNKNOWN', 'MANUAL', 'COMPLETION'));

CREATE FUNCTION enforce_diary_event_content_type() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    referenced_content_type VARCHAR(20);
BEGIN
    SELECT type INTO referenced_content_type
    FROM contents
    WHERE id = NEW.content_id;

    IF referenced_content_type IS NOT NULL
       AND referenced_content_type NOT IN ('MOVIE', 'EPISODE') THEN
        RAISE EXCEPTION 'Diary entries can only reference MOVIE or EPISODE content'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_diary_entries_event_content_type';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_diary_entries_event_content_type
    BEFORE INSERT OR UPDATE OF content_id ON diary_entries
    FOR EACH ROW
    EXECUTE FUNCTION enforce_diary_event_content_type();

CREATE FUNCTION enforce_content_type_immutable() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.type IS DISTINCT FROM OLD.type THEN
        RAISE EXCEPTION 'Content type is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_contents_type_immutable';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_contents_type_immutable
    BEFORE UPDATE OF type ON contents
    FOR EACH ROW
    EXECUTE FUNCTION enforce_content_type_immutable();
