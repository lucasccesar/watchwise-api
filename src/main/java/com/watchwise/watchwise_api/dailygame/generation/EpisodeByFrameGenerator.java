package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeImages;
import com.watchwise.watchwise_api.common.tmdb.TmdbStill;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class EpisodeByFrameGenerator implements DailyChallengeGenerator {

    private static final int MINIMUM_STILL_COUNT = 6;

    private final TmdbClient tmdbClient;
    private final DailyChallengeSnapshotAssembler snapshotAssembler;

    public EpisodeByFrameGenerator(TmdbClient tmdbClient, DailyChallengeSnapshotAssembler snapshotAssembler) {
        this.tmdbClient = tmdbClient;
        this.snapshotAssembler = snapshotAssembler;
    }

    @Override
    public DailyGameType gameType() {
        return DailyGameType.EPISODE_BY_FRAME;
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate) {
        return generate(challengeDate, Set.of());
    }

    @Override
    public Optional<DailyChallengeCandidate> generate(LocalDate challengeDate, Set<String> excludedAnswerKeys) {
        List<TmdbTvSearchResult> series = DailyChallengeGenerationSupport.value(
                        tmdbClient.getTopRatedSeries(DailyChallengeGenerationSupport.randomPage(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .map(page -> shuffled(page.results()))
                .orElse(List.of());
        return series.stream()
                .filter(item -> item != null && DailyChallengeGenerationSupport.validId(item.id())
                        && !DailyChallengeGenerationSupport.isAsianOriginalLanguage(item.originalLanguage()))
                .map(item -> findEpisode(item, challengeDate, excludedAnswerKeys))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<DailyChallengeCandidate> findEpisode(TmdbTvSearchResult series, LocalDate challengeDate,
                                                          Set<String> excludedAnswerKeys) {
        TmdbLookup<TmdbTvFullDetails> lookup = new TmdbLookup<>(tmdbClient.getTvFullDetails(series.id(),
                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE));
        return lookup.value()
                .filter(details -> !DailyChallengeGenerationSupport.isAsianOriginalLanguage(
                        details.originalLanguage()))
                .flatMap(details -> findEpisode(series.id(), details, challengeDate, excludedAnswerKeys));
    }

    private Optional<DailyChallengeCandidate> findEpisode(String seriesTmdbId, TmdbTvFullDetails series,
                                                          LocalDate challengeDate, Set<String> excludedAnswerKeys) {
        if (series.seasons() == null) {
            return Optional.empty();
        }
        for (TmdbSeasonSummary season : shuffled(series.seasons().stream()
                .filter(this::isRegularSeason)
                .toList())) {
            Optional<TmdbSeasonFullDetails> seasonDetails = DailyChallengeGenerationSupport.value(
                    tmdbClient.getSeasonFullDetails(seriesTmdbId, season.seasonNumber(),
                            TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE));
            if (seasonDetails.isEmpty()) {
                continue;
            }
            List<EpisodeCandidate> episodes = new ArrayList<>();
            addReleasedEpisodes(episodes, seasonDetails.get(), challengeDate);
            for (EpisodeCandidate episode : shuffled(episodes)) {
                String answerKey = "EPISODE:" + seriesTmdbId + ":" + episode.seasonNumber()
                        + ":" + episode.episodeNumber();
                if (excludedAnswerKeys != null && excludedAnswerKeys.contains(answerKey)) {
                    continue;
                }
                List<String> paths = imagePaths(seriesTmdbId, episode);
                if (paths.size() >= MINIMUM_STILL_COUNT) {
                    return Optional.of(snapshotAssembler.episode(seriesTmdbId, episode.seasonNumber(),
                            episode.episodeNumber(), episode.name(), series.name(), series.posterPath(),
                            seriesYear(series.firstAirDate()), paths));
                }
            }
        }
        return Optional.empty();
    }

    private List<String> imagePaths(String seriesTmdbId, EpisodeCandidate episode) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        DailyChallengeGenerationSupport.value(tmdbClient.getEpisodeImages(
                        seriesTmdbId, episode.seasonNumber(), episode.episodeNumber()))
                .map(TmdbEpisodeImages::stills)
                .orElse(List.of())
                .stream()
                .filter(still -> still != null && still.filePath() != null && !still.filePath().isBlank())
                .map(TmdbStill::filePath)
                .map(String::trim)
                .forEach(paths::add);
        List<String> ordered = new ArrayList<>(paths);
        Collections.reverse(ordered);
        return List.copyOf(ordered);
    }

    private boolean isRegularSeason(TmdbSeasonSummary season) {
        return season != null && season.seasonNumber() != null && season.seasonNumber() > 0;
    }

    private void addReleasedEpisodes(List<EpisodeCandidate> candidates, TmdbSeasonFullDetails season,
                                     LocalDate challengeDate) {
        if (season.seasonNumber() == null || season.episodes() == null) {
            return;
        }
        season.episodes().stream()
                .filter(episode -> isReleasedEpisode(episode, challengeDate))
                .forEach(episode -> candidates.add(new EpisodeCandidate(season.seasonNumber(), episode.episodeNumber(),
                        episode.name())));
    }

    private boolean isReleasedEpisode(TmdbEpisodeSummary episode, LocalDate challengeDate) {
        return episode != null && episode.episodeNumber() != null && episode.episodeNumber() > 0
                && episode.name() != null && !episode.name().isBlank()
                && DailyChallengeGenerationSupport.date(episode.airDate())
                .map(releaseDate -> !releaseDate.isAfter(challengeDate)).orElse(false);
    }

    private Integer seriesYear(String firstAirDate) {
        return DailyChallengeGenerationSupport.date(firstAirDate).map(LocalDate::getYear).orElse(null);
    }

    private <T> List<T> shuffled(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> shuffled = new ArrayList<>(values);
        Collections.shuffle(shuffled);
        return shuffled;
    }

    private record EpisodeCandidate(Integer seasonNumber, Integer episodeNumber, String name) {
    }

    private record TmdbLookup<T>(com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult<T> result) {
        Optional<T> value() {
            return DailyChallengeGenerationSupport.value(result);
        }
    }
}
