CREATE TABLE import_jobs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    input_kind VARCHAR(30) NOT NULL,
    source_format VARCHAR(40) NOT NULL,
    platform VARCHAR(20),
    source_account_id VARCHAR(255),
    source_namespace VARCHAR(255) NOT NULL,
    state VARCHAR(40) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    preview_version BIGINT NOT NULL DEFAULT 0,
    selection_json TEXT NOT NULL,
    zip_digest VARCHAR(128),
    timezone VARCHAR(100) NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    upload_size_bytes BIGINT NOT NULL DEFAULT 0 CHECK (upload_size_bytes >= 0),
    checkpoint TEXT,
    work_phase VARCHAR(40),
    work_pending BOOLEAN NOT NULL DEFAULT FALSE,
    next_run_at TIMESTAMP,
    lease_token UUID,
    lease_until TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    CONSTRAINT fk_import_jobs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_import_jobs_input_platform_format CHECK (
        (input_kind = 'PICKS_TEMPLATE' AND source_format = 'WATCHWISE_TEMPLATE_JSON' AND platform IS NULL)
        OR (platform IS NOT NULL AND (
            (input_kind = 'PLATFORM_ARCHIVE' AND source_format = 'TRAKT_ZIP' AND platform = 'TRAKT')
            OR (input_kind = 'PLATFORM_ARCHIVE' AND source_format = 'LETTERBOXD_ZIP' AND platform = 'LETTERBOXD')
            OR (input_kind IN ('SINGLE_LIST', 'SINGLE_WATCHLIST') AND source_format = 'TRAKT_JSON' AND platform = 'TRAKT')
            OR (input_kind IN ('SINGLE_LIST', 'SINGLE_WATCHLIST') AND source_format = 'LETTERBOXD_CSV' AND platform = 'LETTERBOXD')
        ))
    )
);
CREATE INDEX idx_import_jobs_runnable ON import_jobs(next_run_at, id) WHERE work_pending = true;
CREATE UNIQUE INDEX uq_import_jobs_one_writer_per_user ON import_jobs(user_id) WHERE state = 'IMPORTING';
CREATE INDEX idx_import_jobs_user_created ON import_jobs(user_id, created_at DESC, id DESC);

CREATE TABLE import_items (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL,
    plan_generation BIGINT NOT NULL,
    source_path VARCHAR(1000),
    record_locator VARCHAR(1000),
    normalized_kind VARCHAR(40) NOT NULL,
    source_namespace VARCHAR(255) NOT NULL,
    object_kind VARCHAR(255) NOT NULL,
    source_id VARCHAR(1000) NOT NULL,
    source_event_key VARCHAR(1200),
    parent_source_id VARCHAR(1000),
    normalized_content_key VARCHAR(1000),
    resolved_content_key VARCHAR(1000),
    parser_version VARCHAR(100),
    resolved_target_json TEXT,
    payload_json TEXT,
    decision VARCHAR(40),
    outcome VARCHAR(40),
    issue TEXT,
    ordinal BIGINT NOT NULL,
    CONSTRAINT fk_import_items_job FOREIGN KEY (job_id) REFERENCES import_jobs(id) ON DELETE CASCADE
);
CREATE INDEX idx_import_items_parent_order ON import_items(job_id, parent_source_id, ordinal, id);
CREATE INDEX idx_import_items_keyset ON import_items(job_id, plan_generation, ordinal, id);
CREATE INDEX idx_import_items_source_event ON import_items(job_id, plan_generation, source_event_key, ordinal, id);
CREATE INDEX idx_import_items_resolution ON import_items(job_id, plan_generation, normalized_content_key, ordinal, id);

CREATE TABLE external_import_links (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    source_namespace VARCHAR(255) NOT NULL,
    object_kind VARCHAR(255) NOT NULL,
    source_id VARCHAR(1000) NOT NULL,
    state VARCHAR(20) NOT NULL,
    owning_job_id UUID,
    target_type VARCHAR(40),
    target_id UUID,
    payload_hash VARCHAR(128),
    outcome_reason TEXT,
    parent_link_id UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_external_import_links_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_external_import_links_job FOREIGN KEY (owning_job_id) REFERENCES import_jobs(id) ON DELETE SET NULL,
    CONSTRAINT fk_external_import_links_parent FOREIGN KEY (parent_link_id) REFERENCES external_import_links(id) ON DELETE SET NULL,
    CONSTRAINT ck_external_import_links_state CHECK (state IN ('IN_PROGRESS', 'APPLIED', 'SKIPPED', 'TOMBSTONE'))
);
CREATE UNIQUE INDEX uq_external_import_links_origin ON external_import_links(user_id, source_namespace, object_kind, source_id);
CREATE INDEX idx_external_import_links_job ON external_import_links(owning_job_id);
CREATE INDEX idx_external_import_links_target ON external_import_links(user_id, target_type, target_id);

CREATE TABLE external_content_aliases (
    id UUID PRIMARY KEY,
    user_id UUID,
    scope_key VARCHAR(100) NOT NULL,
    target_kind VARCHAR(20) NOT NULL,
    original_title VARCHAR(500) NOT NULL,
    normalized_title VARCHAR(500) NOT NULL,
    release_year INTEGER,
    target_key VARCHAR(500) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_tmdb_id VARCHAR(20),
    series_tmdb_id VARCHAR(20),
    season_number INTEGER,
    episode_number INTEGER,
    person_tmdb_id VARCHAR(20),
    provenance VARCHAR(500) NOT NULL,
    trust VARCHAR(40) NOT NULL,
    seed_version VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_external_content_aliases_scope CHECK (scope_key = 'GLOBAL' OR scope_key LIKE 'USER:%'),
    CONSTRAINT fk_external_content_aliases_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_external_content_aliases_scope_owner CHECK ((scope_key = 'GLOBAL' AND user_id IS NULL) OR (scope_key = 'USER:' || user_id::text AND user_id IS NOT NULL)),
    CONSTRAINT ck_external_content_aliases_typed_target CHECK (
        (target_kind = 'CONTENT' AND target_type = 'MOVIE'
            AND target_tmdb_id IS NOT NULL AND btrim(target_tmdb_id) <> '' AND position('|' in target_tmdb_id) = 0
            AND series_tmdb_id IS NULL AND season_number IS NULL AND episode_number IS NULL AND person_tmdb_id IS NULL
            AND target_key = 'CONTENT|MOVIE|' || target_tmdb_id)
        OR (target_kind = 'CONTENT' AND target_type = 'SERIES'
            AND target_tmdb_id IS NOT NULL AND btrim(target_tmdb_id) <> '' AND position('|' in target_tmdb_id) = 0
            AND series_tmdb_id IS NULL AND season_number IS NULL AND episode_number IS NULL AND person_tmdb_id IS NULL
            AND target_key = 'CONTENT|SERIES|' || target_tmdb_id)
        OR (target_kind = 'CONTENT' AND target_type = 'SEASON'
            AND target_tmdb_id IS NULL AND series_tmdb_id IS NOT NULL AND btrim(series_tmdb_id) <> ''
            AND position('|' in series_tmdb_id) = 0 AND season_number IS NOT NULL AND season_number >= 0 AND episode_number IS NULL AND person_tmdb_id IS NULL
            AND target_key = 'CONTENT|SEASON|' || series_tmdb_id || '|' || season_number::text)
        OR (target_kind = 'CONTENT' AND target_type = 'EPISODE'
            AND target_tmdb_id IS NULL AND series_tmdb_id IS NOT NULL AND btrim(series_tmdb_id) <> ''
            AND position('|' in series_tmdb_id) = 0 AND season_number IS NOT NULL AND season_number >= 0
            AND episode_number IS NOT NULL AND episode_number > 0 AND person_tmdb_id IS NULL
            AND target_key = 'CONTENT|EPISODE|' || series_tmdb_id || '|' || season_number::text || '|' || episode_number::text)
        OR (target_kind = 'PERSON' AND target_type = 'PERSON'
            AND person_tmdb_id IS NOT NULL AND btrim(person_tmdb_id) <> '' AND position('|' in person_tmdb_id) = 0
            AND target_tmdb_id IS NULL AND series_tmdb_id IS NULL AND season_number IS NULL AND episode_number IS NULL
            AND target_key = 'PERSON|' || person_tmdb_id)
    )
);
CREATE UNIQUE INDEX uq_external_content_aliases_candidate ON external_content_aliases(scope_key, target_kind, normalized_title, release_year, target_key) NULLS NOT DISTINCT;
CREATE INDEX idx_external_content_aliases_lookup ON external_content_aliases(target_kind, normalized_title, release_year, scope_key);

CREATE TABLE imported_watch_assertions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    content_id UUID NOT NULL,
    source_namespace VARCHAR(255) NOT NULL,
    object_kind VARCHAR(255) NOT NULL,
    source_id VARCHAR(1000) NOT NULL,
    declared_date DATE,
    declared_plays INTEGER,
    coverage VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_imported_watch_assertions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_imported_watch_assertions_content FOREIGN KEY (content_id) REFERENCES contents(id) ON DELETE CASCADE,
    CONSTRAINT uq_imported_watch_assertions_source UNIQUE (user_id, source_namespace, object_kind, source_id),
    CONSTRAINT ck_imported_watch_assertions_coverage CHECK (coverage = 'UNKNOWN'),
    CONSTRAINT ck_imported_watch_assertions_plays CHECK (declared_plays IS NULL OR declared_plays > 0)
);
CREATE INDEX idx_imported_watch_assertions_content ON imported_watch_assertions(user_id, content_id);

CREATE TABLE import_deferred_records (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    source_namespace VARCHAR(255) NOT NULL,
    object_kind VARCHAR(255) NOT NULL,
    source_id VARCHAR(1000) NOT NULL,
    normalized_payload TEXT NOT NULL,
    provenance TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_import_deferred_records_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_import_deferred_records_source UNIQUE (user_id, source_namespace, object_kind, source_id)
);
CREATE INDEX idx_import_deferred_records_user ON import_deferred_records(user_id, created_at, id);

CREATE TABLE letterboxd_seed_loads (
    id UUID PRIMARY KEY,
    seed_version VARCHAR(100) NOT NULL,
    checksum VARCHAR(128) NOT NULL,
    loaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_letterboxd_seed_loads_version_checksum UNIQUE (seed_version, checksum)
);

CREATE TABLE import_file_cleanup_tasks (
    id UUID PRIMARY KEY,
    storage_key VARCHAR(500) NOT NULL,
    ready_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_import_file_cleanup_tasks_generated_key CHECK (storage_key !~ '(^/|^[A-Za-z]:|\\\\|\.\.)')
);
CREATE INDEX idx_import_file_cleanup_tasks_ready ON import_file_cleanup_tasks(ready_at, id);
