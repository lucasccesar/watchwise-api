CREATE TABLE followed_people (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    person_tmdb_id VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_followed_people_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_followed_people_user_id_person_tmdb_id UNIQUE (user_id, person_tmdb_id)
);

CREATE INDEX idx_followed_people_user_id ON followed_people (user_id);