CREATE TABLE picks_templates (
    id UUID PRIMARY KEY,
    creator_id UUID,
    origin VARCHAR(20) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    cover_image VARCHAR(2048),
    instructions TEXT,
    eligibility_start_date DATE,
    eligibility_end_date DATE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_picks_templates_creator FOREIGN KEY (creator_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT ck_picks_templates_origin CHECK (origin IN ('OFFICIAL', 'COMMUNITY')),
    CONSTRAINT ck_picks_templates_dates CHECK ((eligibility_start_date IS NULL AND eligibility_end_date IS NULL) OR eligibility_start_date <= eligibility_end_date)
);

CREATE INDEX idx_picks_templates_origin_name ON picks_templates(origin, name);

CREATE TABLE picks_template_categories (
    id UUID PRIMARY KEY,
    picks_template_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    description TEXT,
    category_group VARCHAR(20) NOT NULL,
    display_order INTEGER NOT NULL,
    allowed_type VARCHAR(20) NOT NULL,
    option_mode VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_picks_template_categories_template FOREIGN KEY (picks_template_id) REFERENCES picks_templates(id) ON DELETE CASCADE,
    CONSTRAINT ck_picks_template_categories_group CHECK (category_group IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT ck_picks_template_categories_allowed_type CHECK (allowed_type IN ('MOVIE', 'SERIES', 'EPISODE', 'PERSON')),
    CONSTRAINT ck_picks_template_categories_option_mode CHECK (option_mode IN ('OPEN', 'FIXED'))
);

CREATE INDEX idx_picks_template_categories_template_order ON picks_template_categories(picks_template_id, display_order);

CREATE TABLE picks_template_options (
    id UUID PRIMARY KEY,
    category_id UUID NOT NULL,
    content_id UUID,
    person_tmdb_id VARCHAR(20),
    context_content_id UUID,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_picks_template_options_category FOREIGN KEY (category_id) REFERENCES picks_template_categories(id) ON DELETE CASCADE,
    CONSTRAINT fk_picks_template_options_content FOREIGN KEY (content_id) REFERENCES contents(id) ON DELETE RESTRICT,
    CONSTRAINT fk_picks_template_options_context_content FOREIGN KEY (context_content_id) REFERENCES contents(id) ON DELETE RESTRICT,
    CONSTRAINT ck_picks_template_options_one_target CHECK ((CASE WHEN content_id IS NOT NULL THEN 1 ELSE 0 END) + (CASE WHEN person_tmdb_id IS NOT NULL THEN 1 ELSE 0 END) = 1),
    CONSTRAINT ck_picks_template_options_context_is_person CHECK (context_content_id IS NULL OR person_tmdb_id IS NOT NULL),
    CONSTRAINT uq_picks_template_options_fixed_target UNIQUE NULLS NOT DISTINCT (category_id, content_id, person_tmdb_id, context_content_id)
);

CREATE INDEX idx_picks_template_options_category ON picks_template_options(category_id);

CREATE TABLE picks (
    id UUID PRIMARY KEY,
    picks_template_id UUID NOT NULL,
    user_id UUID NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_picks_template FOREIGN KEY (picks_template_id) REFERENCES picks_templates(id) ON DELETE RESTRICT,
    CONSTRAINT fk_picks_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_picks_visibility CHECK (visibility IN ('PUBLIC', 'FOLLOWERS', 'PRIVATE'))
);

CREATE INDEX idx_picks_user_template ON picks(user_id, picks_template_id);

CREATE TABLE pick_selections (
    id UUID PRIMARY KEY,
    pick_id UUID NOT NULL,
    category_id UUID NOT NULL,
    content_id UUID,
    person_tmdb_id VARCHAR(20),
    context_content_id UUID,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_pick_selections_pick FOREIGN KEY (pick_id) REFERENCES picks(id) ON DELETE CASCADE,
    CONSTRAINT fk_pick_selections_category FOREIGN KEY (category_id) REFERENCES picks_template_categories(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pick_selections_content FOREIGN KEY (content_id) REFERENCES contents(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pick_selections_context_content FOREIGN KEY (context_content_id) REFERENCES contents(id) ON DELETE RESTRICT,
    CONSTRAINT ck_pick_selections_one_target CHECK ((CASE WHEN content_id IS NOT NULL THEN 1 ELSE 0 END) + (CASE WHEN person_tmdb_id IS NOT NULL THEN 1 ELSE 0 END) = 1),
    CONSTRAINT ck_pick_selections_context_is_person CHECK (context_content_id IS NULL OR person_tmdb_id IS NOT NULL),
    CONSTRAINT uq_pick_selections_pick_category UNIQUE (pick_id, category_id)
);

CREATE INDEX idx_pick_selections_pick_category ON pick_selections(pick_id, category_id);
