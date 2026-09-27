package com.watchwise.watchwise_api.dailygame.generation;

import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbCreator;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbProductionCompany;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameTargetKind;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DailyChallengeSnapshotAssembler {

    private final ObjectMapper objectMapper;

    public DailyChallengeSnapshotAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DailyChallengeCandidate moviePoster(TmdbMovieSearchResult movie, String imagePath) {
        return candidate(DailyGameType.MOVIE_BY_POSTER, DailyGameTargetKind.MOVIE, movie.id(), null, null, null,
                null, "MOVIE:" + movie.id(), imagePath,
                answerSnapshot(DailyGameTargetKind.MOVIE, movie.id(), null, null, null, null, movie.title(), imagePath, null),
                displaySnapshot(imagePath), List.of());
    }

    public DailyChallengeCandidate seriesPoster(TmdbTvSearchResult series, String imagePath) {
        return candidate(DailyGameType.SERIES_BY_POSTER, DailyGameTargetKind.SERIES, series.id(), null, null, null,
                null, "SERIES:" + series.id(), imagePath,
                answerSnapshot(DailyGameTargetKind.SERIES, series.id(), null, null, null, null, series.name(), imagePath, null),
                displaySnapshot(imagePath), List.of());
    }

    public DailyChallengeCandidate person(DailyGameType gameType, String personTmdbId, String name, String imagePath,
                                          String sourceTmdbId, List<DailyChallengeCandidate.HintSnapshot> hints) {
        return candidate(gameType, DailyGameTargetKind.PERSON, personTmdbId, null, null, null, sourceTmdbId,
                "PERSON:" + personTmdbId, imagePath,
                answerSnapshot(DailyGameTargetKind.PERSON, null, personTmdbId, null, null, null, name, imagePath,
                        sourceTmdbId),
                displaySnapshot(imagePath), hints);
    }

    public DailyChallengeCandidate episode(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber,
                                           String name, String imagePath) {
        return candidate(DailyGameType.EPISODE_BY_FRAME, DailyGameTargetKind.EPISODE, null, seriesTmdbId,
                seasonNumber, episodeNumber, seriesTmdbId,
                "EPISODE:" + seriesTmdbId + ":" + seasonNumber + ":" + episodeNumber, imagePath,
                answerSnapshot(DailyGameTargetKind.EPISODE, null, null, seriesTmdbId, seasonNumber, episodeNumber,
                        name, imagePath, seriesTmdbId),
                displaySnapshot(imagePath), List.of());
    }

    public DailyChallengeCandidate movieInfo(TmdbMovieFullDetails movie, String imagePath,
                                             List<DailyChallengeCandidate.HintSnapshot> hints) {
        return candidate(DailyGameType.MOVIE_BY_INFO, DailyGameTargetKind.MOVIE, movie.id(), null, null, null,
                movie.id(), "MOVIE:" + movie.id(), imagePath,
                answerSnapshot(DailyGameTargetKind.MOVIE, movie.id(), null, null, null, null, movie.title(), imagePath,
                        movie.id()),
                displaySnapshot(imagePath), hints);
    }

    public DailyChallengeCandidate seriesInfo(TmdbTvFullDetails series, String imagePath,
                                              List<DailyChallengeCandidate.HintSnapshot> hints) {
        return candidate(DailyGameType.SERIES_BY_INFO, DailyGameTargetKind.SERIES, series.id(), null, null, null,
                series.id(), "SERIES:" + series.id(), imagePath,
                answerSnapshot(DailyGameTargetKind.SERIES, series.id(), null, null, null, null, series.name(), imagePath,
                        series.id()),
                displaySnapshot(imagePath), hints);
    }

    public DailyChallengeCandidate actorFromMovie(String movieTmdbId, TmdbCastMember actor, String imagePath) {
        return person(DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY, String.valueOf(actor.id()), actor.name(), imagePath,
                movieTmdbId, List.of());
    }

    public DailyChallengeCandidate actorFromSeries(String seriesTmdbId, TmdbAggregateCastMember actor, String imagePath) {
        return person(DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY, String.valueOf(actor.id()), actor.name(), imagePath,
                seriesTmdbId, List.of());
    }

    public JsonNode answerSnapshot(DailyGameTargetKind targetKind, String targetTmdbId, String personTmdbId,
                                   String seriesTmdbId, Integer seasonNumber, Integer episodeNumber, String title,
                                   String imagePath, String sourceTmdbId) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("targetKind", targetKind.name());
        put(snapshot, "tmdbId", targetTmdbId);
        put(snapshot, "personTmdbId", personTmdbId);
        put(snapshot, "seriesTmdbId", seriesTmdbId);
        if (seasonNumber != null) {
            snapshot.put("seasonNumber", seasonNumber);
        }
        if (episodeNumber != null) {
            snapshot.put("episodeNumber", episodeNumber);
        }
        put(snapshot, "title", title);
        put(snapshot, "imageUrl", imagePath);
        put(snapshot, "sourceTmdbId", sourceTmdbId);
        return snapshot;
    }

    public JsonNode displaySnapshot(String imagePath) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("imageUrl", imagePath);
        return snapshot;
    }

    public static DailyChallengeCandidate.HintSnapshot hint(String type, String value) {
        return new DailyChallengeCandidate.HintSnapshot(type, value);
    }

    private DailyChallengeCandidate candidate(DailyGameType gameType, DailyGameTargetKind targetKind,
                                              String targetTmdbId, String seriesTmdbId, Integer seasonNumber,
                                              Integer episodeNumber, String sourceTmdbId, String answerKey,
                                              String imagePath, JsonNode answerSnapshot, JsonNode displaySnapshot,
                                              List<DailyChallengeCandidate.HintSnapshot> hints) {
        return new DailyChallengeCandidate(gameType, targetKind, targetTmdbId, seriesTmdbId, seasonNumber,
                episodeNumber, sourceTmdbId, answerKey, imagePath, answerSnapshot, displaySnapshot, hints);
    }

    private static void put(ObjectNode objectNode, String field, String value) {
        if (value != null && !value.isBlank()) {
            objectNode.put(field, value);
        }
    }
}
