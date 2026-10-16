package com.watchwise.watchwise_api.person.service.impl;

import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateRole;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregate;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregateCredit;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.person.entity.PersonParticipation;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PersonFilmographyNormalizer {
    public List<PersonFilmographyCredit> normalize(TmdbPersonAggregate aggregate) {
        Map<CreditKey, MutableCredit> merged = new LinkedHashMap<>();
        var combined = aggregate.combinedCredits();
        if (combined != null) {
            merge(combined.cast(), true, merged);
            merge(combined.crew(), false, merged);
        }
        return merged.values().stream().map(MutableCredit::toCredit).toList();
    }

    private void merge(List<TmdbPersonAggregateCredit> credits, boolean cast, Map<CreditKey, MutableCredit> merged) {
        if (credits == null) return;
        for (TmdbPersonAggregateCredit credit : credits) {
            MovieOrSeriesType type = switch (credit.mediaType() == null ? "" : credit.mediaType()) {
                case "movie" -> MovieOrSeriesType.MOVIE;
                case "tv" -> MovieOrSeriesType.SERIES;
                default -> null;
            };
            if (type == null || credit.id() == null || !credit.id().matches("\\d+")) continue;
            merged.computeIfAbsent(new CreditKey(type, credit.id()), MutableCredit::new).add(credit, cast);
        }
    }

    private record CreditKey(MovieOrSeriesType type, String tmdbId) { }

    private static final class MutableCredit {
        private final CreditKey key;
        private String title;
        private String posterPath;
        private String backdropPath;
        private LocalDate releaseDate;
        private Double popularity;
        private Integer creditedEpisodeCount;
        private final Set<String> characters = new LinkedHashSet<>();
        private final Set<String> jobs = new LinkedHashSet<>();
        private boolean hasCast;
        private boolean hasCrew;

        private MutableCredit(CreditKey key) { this.key = key; }

        private void add(TmdbPersonAggregateCredit credit, boolean cast) {
            if (cast) {
                hasCast = true;
                addIfPresent(characters, credit.character());
                if (credit.roles() != null) for (TmdbAggregateRole role : credit.roles()) {
                    if (role != null) addIfPresent(characters, role.character());
                }
            } else {
                hasCrew = true;
                addIfPresent(jobs, credit.job());
            }
            String candidateTitle = key.type() == MovieOrSeriesType.MOVIE ? credit.title() : credit.name();
            if (isBlank(title) && !isBlank(candidateTitle)) title = candidateTitle;
            if (isBlank(posterPath) && !isBlank(credit.posterPath())) posterPath = credit.posterPath();
            if (isBlank(backdropPath) && !isBlank(credit.backdropPath())) backdropPath = credit.backdropPath();
            if (popularity == null && credit.popularity() != null) popularity = credit.popularity();
            if (creditedEpisodeCount == null && credit.episodeCount() != null) creditedEpisodeCount = credit.episodeCount();
            LocalDate candidateDate = parseDate(key.type() == MovieOrSeriesType.MOVIE
                    ? credit.releaseDate() : credit.firstAirDate());
            if (releaseDate == null && candidateDate != null) releaseDate = candidateDate;
        }

        private PersonFilmographyCredit toCredit() {
            PersonParticipation participation = hasCast && hasCrew ? PersonParticipation.ALL
                    : hasCast ? PersonParticipation.CAST : PersonParticipation.CREW;
            return new PersonFilmographyCredit(key.type(), key.tmdbId(), title, posterPath, backdropPath,
                    releaseDate, popularity, creditedEpisodeCount, new ArrayList<>(characters),
                    new ArrayList<>(jobs), participation);
        }

        private static void addIfPresent(Set<String> values, String value) {
            if (!isBlank(value)) values.add(value);
        }
        private static boolean isBlank(String value) { return value == null || value.isBlank(); }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE); }
        catch (DateTimeParseException ignored) { return null; }
    }
}
