ALTER TABLE contents ADD COLUMN is_season_finale BOOLEAN;
ALTER TABLE contents ADD COLUMN is_series_finale BOOLEAN;
ALTER TABLE diary_entries ADD COLUMN auto_generated BOOLEAN NOT NULL DEFAULT FALSE;