package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class EpisodeByFrameGenerator implements DailyChallengeGenerator {

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
        return DailyChallengeGenerationSupport.randomItem(
                        tmdbClient.getPopularSeries(DailyChallengeGenerationSupport.randomPage(),
                                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                .filter(series -> series != null && DailyChallengeGenerationSupport.validId(series.id()))
                .flatMap(series -> findEpisode(series, challengeDate, excludedAnswerKeys));
    }

    private Optional<DailyChallengeCandidate> findEpisode(TmdbTvSearchResult series, LocalDate challengeDate,
                                                          Set<String> excludedAnswerKeys) {
        TmdbLookup<TmdbTvFullDetails> lookup = new TmdbLookup<>(tmdbClient.getTvFullDetails(series.id(),
                TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE));
        return lookup.value()
                .flatMap(details -> findEpisode(series.id(), details, challengeDate, excludedAnswerKeys));
    }

    private Optional<DailyChallengeCandidate> findEpisode(String seriesTmdbId, TmdbTvFullDetails series,
                                                          LocalDate challengeDate, Set<String> excludedAnswerKeys) {
        if (series.seasons() == null) {
            return Optional.empty();
        }
        List<EpisodeCandidate> candidates = new ArrayList<>();
        series.seasons().stream()
                .filter(this::isRegularSeason)
                .forEach(season -> DailyChallengeGenerationSupport.value(
                                tmdbClient.getSeasonFullDetails(seriesTmdbId, season.seasonNumber(),
                                        TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE))
                        .ifPresent(details -> addReleasedEpisodes(candidates, details, challengeDate)));
        return DailyChallengeGenerationSupport.randomItem(candidates, episode ->
                        !excludedAnswerKeys.contains("EPISODE:" + seriesTmdbId + ":" + episode.seasonNumber()
                                + ":" + episode.episodeNumber()))
                .map(episode -> snapshotAssembler.episode(seriesTmdbId, episode.seasonNumber(), episode.episodeNumber(),
                        episode.name(), TmdbImageUrlBuilder.stillUrl(episode.stillPath())));
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
                        episode.name(), episode.stillPath())));
    }

    private boolean isReleasedEpisode(TmdbEpisodeSummary episode, LocalDate challengeDate) {
        return episode != null && episode.episodeNumber() != null && episode.episodeNumber() > 0
                && episode.name() != null && !episode.name().isBlank()
                && DailyChallengeGenerationSupport.validImage(episode.stillPath())
                && DailyChallengeGenerationSupport.date(episode.airDate())
                .map(releaseDate -> !releaseDate.isAfter(challengeDate)).orElse(false);
    }

    private record EpisodeCandidate(Integer seasonNumber, Integer episodeNumber, String name, String stillPath) {
    }

    private record TmdbLookup<T>(com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult<T> result) {
        Optional<T> value() {
            return DailyChallengeGenerationSupport.value(result);
        }
    }
}
