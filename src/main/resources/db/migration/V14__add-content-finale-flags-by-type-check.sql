ALTER TABLE contents ADD CONSTRAINT ck_contents_finale_flags_by_type CHECK (
    (is_season_finale IS NULL OR type = 'EPISODE')
    AND (is_series_finale IS NULL OR type IN ('EPISODE', 'SEASON'))
);