package com.watchwise.watchwise_api.seriesprogress.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.diaryentry.dto.SeasonProgressDTO;
import com.watchwise.watchwise_api.diaryentry.repository.WatchedEpisodeCoordinate;
import com.watchwise.watchwise_api.seriesprogress.dto.ProgressEpisodeDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface SeriesProgressNextEpisodeResolver {

    ProgressEpisodeDTO resolveNext(
            TmdbTvFullDetails seriesDetails,
            String language,
            List<SeasonProgressDTO> seasonProgress,
            Set<WatchedEpisodeCoordinate> watchedCoordinates,
            LocalDate today);
}
