package com.watchwise.watchwise_api.trending.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTrendingMovieResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbTrendingTvResult;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.search.dto.SearchContentDTO;
import com.watchwise.watchwise_api.trending.dto.TrendingResponseDTO;
import com.watchwise.watchwise_api.trending.service.TrendingService;
import com.watchwise.watchwise_api.trending.service.TrendingTimeWindow;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TrendingServiceImpl implements TrendingService {

    private final UserRepository userRepository;
    private final TmdbClient tmdbClient;

    @Override
    public TrendingResponseDTO getTrending(UUID viewerId, TrendingTimeWindow timeWindow, int size) {
        User viewer = userRepository.findById(viewerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        String language = viewer.getPreferredLanguage();
        String window = timeWindow.value();

        List<SearchContentDTO> movies = getResults(tmdbClient.getTrendingMovies(window, language)).stream()
                .limit(size)
                .map(this::toMovieDto)
                .toList();
        List<SearchContentDTO> series = getResults(tmdbClient.getTrendingSeries(window, language)).stream()
                .limit(size)
                .map(this::toSeriesDto)
                .toList();

        return new TrendingResponseDTO(movies, series);
    }

    private SearchContentDTO toMovieDto(TmdbTrendingMovieResult movie) {
        return new SearchContentDTO(movie.id(), MovieOrSeriesType.MOVIE, movie.title(),
                TmdbImageUrlBuilder.posterUrl(movie.posterPath()), releaseYear(movie.releaseDate()));
    }

    private SearchContentDTO toSeriesDto(TmdbTrendingTvResult series) {
        return new SearchContentDTO(series.id(), MovieOrSeriesType.SERIES, series.name(),
                TmdbImageUrlBuilder.posterUrl(series.posterPath()), releaseYear(series.firstAirDate()));
    }

    private <T> List<T> getResults(TmdbLookupResult<TmdbSearchPage<T>> lookup) {
        if (lookup.isUnavailable()) {
            throw new TmdbUnavailableException("TMDB is currently unavailable");
        }
        if (lookup instanceof TmdbLookupResult.Found<?> found && found.value() == null) {
            return List.of();
        }
        return lookup.toOptional().map(TmdbSearchPage::results).orElseGet(List::of);
    }

    private Integer releaseYear(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date).getYear();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
