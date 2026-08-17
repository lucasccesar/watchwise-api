ALTER TABLE contents ADD CONSTRAINT ck_contents_episode_number
    CHECK (episode_number IS NULL OR episode_number >= 1);

ALTER TABLE contents ADD CONSTRAINT ck_contents_season_number
    CHECK (season_number IS NULL OR season_number >= 0);
