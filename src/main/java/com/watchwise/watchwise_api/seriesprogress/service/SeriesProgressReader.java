package com.watchwise.watchwise_api.seriesprogress.service;

import java.util.Collection;
import java.util.UUID;

public interface SeriesProgressReader {

    SeriesProgressReadResult readForSeriesIds(
            UUID viewerId, Collection<String> seriesTmdbIds);
}
