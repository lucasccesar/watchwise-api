CREATE UNIQUE INDEX uq_contents_season_finale
    ON contents (series_tmdb_id, season_number)
    WHERE type = 'EPISODE' AND is_season_finale = TRUE;

CREATE UNIQUE INDEX uq_contents_series_finale
    ON contents (series_tmdb_id)
    WHERE type = 'SEASON' AND is_series_finale = TRUE;
