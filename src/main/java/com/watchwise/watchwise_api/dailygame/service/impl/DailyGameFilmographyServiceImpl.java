package com.watchwise.watchwise_api.dailygame.service.impl;

import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateRole;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregate;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonAggregateCredit;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameActorGuessDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyEntryDTO;
import com.watchwise.watchwise_api.dailygame.dto.DailyGameFilmographyFeedbackDTO;
import com.watchwise.watchwise_api.dailygame.entity.DailyChallenge;
import com.watchwise.watchwise_api.dailygame.entity.DailyGameType;
import com.watchwise.watchwise_api.dailygame.service.DailyGameFilmographyService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class DailyGameFilmographyServiceImpl implements DailyGameFilmographyService {

    private static final String LANGUAGE = TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE;
    private static final String FILMOGRAPHY_KEY = "filmography";

    private final TmdbClient tmdbClient;

    public DailyGameFilmographyServiceImpl(TmdbClient tmdbClient) {
        this.tmdbClient = tmdbClient;
    }

    @Override
    public DailyGameFilmographyFeedbackDTO compare(
            DailyChallenge challenge, String guessedPersonTmdbId, boolean majorRoles) {
        String expectedMediaType = mediaType(challenge.getGameType());
        List<Work> secret = readSnapshot(challenge, expectedMediaType);
        TmdbPersonAggregate guessedActor = requireAggregate(guessedPersonTmdbId);
        List<Work> guessed = normalize(guessedActor, expectedMediaType);

        Set<String> secretAllKeys = keys(secret);
        Set<String> guessedAllKeys = keys(guessed);
        Set<String> sharedAll = intersection(secretAllKeys, guessedAllKeys);
        Set<String> sharedMajor = isSeries(challenge.getGameType())
                ? intersection(keys(filterMajorRoles(secret)), keys(filterMajorRoles(guessed)))
                : sharedAll;
        List<Work> selectedSecret = isSeries(challenge.getGameType()) && majorRoles
                ? filterMajorRoles(secret) : secret;
        Set<String> selectedShared = isSeries(challenge.getGameType()) && majorRoles
                ? sharedMajor : sharedAll;

        List<DailyGameFilmographyEntryDTO> entries = selectedSecret.stream()
                .map(work -> toEntry(work, selectedShared.contains(work.key()),
                        selectedShared.contains(work.key())))
                .toList();
        return new DailyGameFilmographyFeedbackDTO(
                new DailyGameActorGuessDTO(guessedPersonTmdbId,
                        usable(guessedActor.name()) ? guessedActor.name() : guessedPersonTmdbId),
                List.copyOf(sharedMajor), List.copyOf(sharedAll), entries);
    }

    @Override
    public FilmographySnapshot snapshot(String personTmdbId, DailyGameType gameType) {
        String expectedMediaType = mediaType(gameType);
        if (expectedMediaType == null) {
            return new FilmographySnapshot(List.of());
        }
        TmdbLookupResult<TmdbPersonAggregate> lookup = tmdbClient.getPersonAggregate(personTmdbId, LANGUAGE);
        if (!(lookup instanceof TmdbLookupResult.Found<TmdbPersonAggregate> found) || found.value() == null) {
            return new FilmographySnapshot(List.of());
        }
        return new FilmographySnapshot(normalize(found.value(), expectedMediaType).stream()
                .map(Work::toSnapshot)
                .toList());
    }

    private TmdbPersonAggregate requireAggregate(String personTmdbId) {
        TmdbLookupResult<TmdbPersonAggregate> lookup = tmdbClient.getPersonAggregate(personTmdbId, LANGUAGE);
        if (lookup instanceof TmdbLookupResult.Found<TmdbPersonAggregate> found && found.value() != null) {
            return found.value();
        }
        throw new TmdbUnavailableException("TMDB is temporarily unavailable");
    }

    private List<Work> normalize(TmdbPersonAggregate aggregate, String expectedMediaType) {
        if (aggregate == null || aggregate.combinedCredits() == null
                || aggregate.combinedCredits().cast() == null) {
            return List.of();
        }
        List<TmdbPersonAggregateCredit> credits = aggregate.combinedCredits().cast();
        Map<String, Integer> totalEpisodesByWork = totalEpisodesByWork(credits, expectedMediaType);
        Map<String, MutableWork> merged = new LinkedHashMap<>();
        for (TmdbPersonAggregateCredit credit : credits) {
            Work work = normalizeCredit(credit, expectedMediaType, totalEpisodesByWork);
            if (work == null) {
                continue;
            }
            merged.computeIfAbsent(work.key(), ignored -> new MutableWork(work)).merge(work);
        }
        return merged.values().stream().map(MutableWork::toWork).toList();
    }

    private Work normalizeCredit(TmdbPersonAggregateCredit credit, String expectedMediaType,
                                 Map<String, Integer> totalEpisodesByWork) {
        if (credit == null || !expectedMediaType.equalsIgnoreCase(credit.mediaType())
                || !validId(credit.id())) {
            return null;
        }
        String title = usable(credit.title()) ? credit.title().trim()
                : usable(credit.name()) ? credit.name().trim() : null;
        String period = "movie".equalsIgnoreCase(expectedMediaType)
                ? usable(credit.releaseDate()) ? credit.releaseDate().trim() : null
                : usable(credit.firstAirDate()) ? credit.firstAirDate().trim() : null;
        Integer year = year(period);
        if (title == null) {
            return null;
        }

        List<TmdbAggregateRole> roles = credit.roles() == null ? List.of() : credit.roles();
        Integer roleEpisodeCount = roles.stream()
                .filter(Objects::nonNull)
                .map(TmdbAggregateRole::episodeCount)
                .filter(Objects::nonNull)
                .filter(value -> value > 0)
                .max(Integer::compareTo)
                .orElse(credit.episodeCount());
        if ("tv".equalsIgnoreCase(expectedMediaType)
                && (roleEpisodeCount == null || roleEpisodeCount < 1)) {
            return null;
        }
        Integer totalEpisodes = "tv".equalsIgnoreCase(expectedMediaType)
                ? totalEpisodesByWork.get(credit.id()) : null;
        String character = character(credit, roles);
        List<String> genres = credit.genreIds() == null ? List.of() : credit.genreIds().stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .distinct()
                .toList();
        return new Work(credit.mediaType().toLowerCase(Locale.ROOT), credit.id(), title, year, genres,
                TmdbImageUrlBuilder.posterUrl(credit.posterPath()),
                "tv".equalsIgnoreCase(expectedMediaType) ? roleEpisodeCount : null,
                totalEpisodes, period, character);
    }

    private Map<String, Integer> totalEpisodesByWork(
            List<TmdbPersonAggregateCredit> credits, String expectedMediaType) {
        if (!"tv".equalsIgnoreCase(expectedMediaType)) {
            return Map.of();
        }
        Map<String, Integer> totals = new LinkedHashMap<>();
        credits.stream()
                .filter(credit -> credit != null && "tv".equalsIgnoreCase(credit.mediaType())
                        && validId(credit.id()))
                .map(TmdbPersonAggregateCredit::id)
                .distinct()
                .forEach(id -> tvTotalEpisodes(id).ifPresent(total -> totals.put(id, total)));
        return totals;
    }

    private java.util.Optional<Integer> tvTotalEpisodes(String seriesTmdbId) {
        TmdbLookupResult<TmdbTvFullDetails> lookup = tmdbClient.getTvFullDetails(seriesTmdbId, LANGUAGE);
        if (lookup instanceof TmdbLookupResult.Found<TmdbTvFullDetails> found
                && found.value() != null && found.value().numberOfEpisodes() != null
                && found.value().numberOfEpisodes() > 0) {
            return java.util.Optional.of(found.value().numberOfEpisodes());
        }
        return java.util.Optional.empty();
    }

    private String character(TmdbPersonAggregateCredit credit, List<TmdbAggregateRole> roles) {
        if (usable(credit.character())) {
            return credit.character().trim();
        }
        return roles.stream()
                .filter(Objects::nonNull)
                .map(TmdbAggregateRole::character)
                .filter(this::usable)
                .map(String::trim)
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse(null);
    }

    private List<Work> readSnapshot(DailyChallenge challenge, String expectedMediaType) {
        if (challenge == null || challenge.getAnswerSnapshot() == null) {
            return List.of();
        }
        Object value = challenge.getAnswerSnapshot().get(FILMOGRAPHY_KEY);
        if (!(value instanceof List<?> rawEntries)) {
            return List.of();
        }
        return rawEntries.stream()
                .map(entry -> snapshotWork(entry, expectedMediaType))
                .filter(Objects::nonNull)
                .toList();
    }

    private Work snapshotWork(Object value, String expectedMediaType) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        String mediaType = string(raw.get("mediaType"));
        if (mediaType == null) {
            mediaType = expectedMediaType;
        }
        String workId = string(raw.get("workId"));
        String title = string(raw.get("title"));
        Integer year = integer(raw.get("year"));
        String period = string(raw.get("period"));
        if (!validId(workId) || !expectedMediaType.equalsIgnoreCase(mediaType) || !usable(title)) {
            return null;
        }
        Integer episodeCount = integer(raw.get("episodeCount"));
        Integer totalEpisodes = integer(raw.get("totalEpisodes"));
        if ("tv".equalsIgnoreCase(expectedMediaType)
                && (episodeCount == null || episodeCount < 1)) {
            return null;
        }
        return new Work(mediaType.toLowerCase(Locale.ROOT), workId, title, year,
                strings(raw.get("genres")), string(raw.get("posterUrl")), episodeCount,
                totalEpisodes, period,
                string(raw.get("character")));
    }

    private List<Work> filterMajorRoles(List<Work> works) {
        return works.stream()
                .filter(work -> work.episodeCount() != null && work.episodeCount() >= majorRoleMinimum(work.totalEpisodes()))
                .toList();
    }

    private int majorRoleMinimum(Integer totalEpisodes) {
        if (totalEpisodes == null || totalEpisodes < 1) {
            return Integer.MAX_VALUE;
        }
        double ratio = totalEpisodes <= 6 ? 0.33d : totalEpisodes <= 20 ? 0.40d : 0.50d;
        return BigDecimal.valueOf(totalEpisodes)
                .multiply(BigDecimal.valueOf(ratio))
                .setScale(0, RoundingMode.CEILING)
                .intValueExact();
    }

    private DailyGameFilmographyEntryDTO toEntry(Work work, boolean revealed, boolean highlighted) {
        return new DailyGameFilmographyEntryDTO(work.id(), revealed ? work.title() : null,
                revealed, highlighted, work.year(), work.genres(), work.posterUrl(), work.episodeCount(),
                work.period(), work.character());
    }

    private Set<String> keys(List<Work> works) {
        return works.stream().map(Work::key).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> intersection(Set<String> left, Set<String> right) {
        return left.stream().filter(right::contains)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private String mediaType(DailyGameType gameType) {
        return gameType == DailyGameType.ACTOR_BY_MOVIE_FILMOGRAPHY ? "movie"
                : gameType == DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY ? "tv" : null;
    }

    private boolean isSeries(DailyGameType gameType) {
        return gameType == DailyGameType.ACTOR_BY_SERIES_FILMOGRAPHY;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(this::string).filter(this::usable).map(String::trim).toList();
    }

    private Integer year(String period) {
        if (!usable(period) || period.trim().length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(period.trim().substring(0, 4));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean validId(String value) {
        return value != null && value.matches("[1-9]\\d*");
    }

    private boolean usable(String value) {
        return value != null && !value.isBlank();
    }

    private static String canonicalWorkKey(String mediaType, String id) {
        String prefix = "tv".equalsIgnoreCase(mediaType) ? "SERIES" : "MOVIE";
        return prefix + ":" + id;
    }

    private record Work(String mediaType, String id, String title, Integer year, List<String> genres,
                        String posterUrl, Integer episodeCount, Integer totalEpisodes, String period,
                        String character) {

        private String key() {
            return canonicalWorkKey(mediaType, id);
        }

        private Map<String, Object> toSnapshot() {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("workId", id);
            snapshot.put("title", title);
            snapshot.put("mediaType", mediaType);
            snapshot.put("year", year);
            snapshot.put("genres", genres);
            snapshot.put("posterUrl", posterUrl);
            snapshot.put("episodeCount", episodeCount);
            snapshot.put("totalEpisodes", totalEpisodes);
            snapshot.put("period", period);
            snapshot.put("character", character);
            return snapshot;
        }
    }

    private static final class MutableWork {

        private final String mediaType;
        private final String id;
        private String title;
        private Integer year;
        private final Set<String> genres = new LinkedHashSet<>();
        private String posterUrl;
        private Integer episodeCount;
        private Integer totalEpisodes;
        private String period;
        private final Set<String> characters = new LinkedHashSet<>();

        private MutableWork(Work work) {
            mediaType = work.mediaType();
            id = work.id();
            merge(work);
        }

        private void merge(Work work) {
            if (!usableValue(title)) title = work.title();
            if (year == null) year = work.year();
            genres.addAll(work.genres());
            if (!usableValue(posterUrl)) posterUrl = work.posterUrl();
            if (episodeCount == null || greater(work.episodeCount(), episodeCount)) episodeCount = work.episodeCount();
            if (totalEpisodes == null || greater(work.totalEpisodes(), totalEpisodes)) totalEpisodes = work.totalEpisodes();
            if (!usableValue(period)) period = work.period();
            if (usableValue(work.character())) characters.add(work.character().trim());
        }

        private Work toWork() {
            return new Work(mediaType, id, title, year, List.copyOf(genres), posterUrl, episodeCount,
                    totalEpisodes, period, characters.isEmpty() ? null : String.join(", ", characters));
        }

        private static boolean greater(Integer candidate, Integer current) {
            return candidate != null && (current == null || candidate > current);
        }

        private static boolean usableValue(String value) {
            return value != null && !value.isBlank();
        }
    }
}
