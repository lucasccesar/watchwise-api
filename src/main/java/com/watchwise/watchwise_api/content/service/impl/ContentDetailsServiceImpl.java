package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCredits;
import com.watchwise.watchwise_api.common.tmdb.TmdbAlternativeTitleEntry;
import com.watchwise.watchwise_api.common.tmdb.TmdbCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbCreator;
import com.watchwise.watchwise_api.common.tmdb.TmdbCredits;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbGenre;
import com.watchwise.watchwise_api.common.tmdb.TmdbGuestStar;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbProductionCountry;
import com.watchwise.watchwise_api.common.tmdb.TmdbProvider;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionProviders;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbWatchProviders;
import com.watchwise.watchwise_api.content.dto.CastMemberDTO;
import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.dto.CreatorDTO;
import com.watchwise.watchwise_api.content.dto.EpisodeSummaryDTO;
import com.watchwise.watchwise_api.content.dto.SeasonSummaryDTO;
import com.watchwise.watchwise_api.content.dto.WatchProviderDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentDetailsService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Service
@RequiredArgsConstructor
public class ContentDetailsServiceImpl implements ContentDetailsService {

    static final int MAX_BATCH_IDS = 100;
    private static final int RECENT_EPISODES_LIMIT = 3;

    private final ContentRepository contentRepository;
    private final UserRepository userRepository;
    private final TmdbClient tmdbClient;
    private final ExecutorService tmdbSeasonFetchExecutor;

    @Override
    public ContentDetailsDTO getDetails(UUID contentId, UUID requestingUserId) {
        return getDetailsBatch(List.of(contentId), requestingUserId).get(0);
    }

    @Override
    public List<ContentDetailsDTO> getDetailsBatch(List<UUID> contentIds, UUID requestingUserId) {
        if (contentIds == null || contentIds.isEmpty()) {
            throw new BadRequestException("ids must not be empty");
        }
        if (contentIds.size() > MAX_BATCH_IDS) {
            throw new BadRequestException("Cannot request details for more than " + MAX_BATCH_IDS + " contents at once");
        }

        User user = userRepository.findById(requestingUserId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        String language = user.getPreferredLanguage();
        String region = user.getPreferredRegion();

        return contentIds.stream()
                .map(contentId -> buildDetails(contentId, language, region))
                .toList();
    }

    private ContentDetailsDTO buildDetails(UUID contentId, String language, String region) {
        Content content = contentRepository.findById(contentId)
                .orElseThrow(() -> new NotFoundException("Content not found"));

        return switch (content.getType()) {
            case MOVIE -> buildMovieDetails(content, language, region);
            case SERIES -> buildSeriesDetails(content, language, region);
            case SEASON -> buildSeasonDetails(content, language, region);
            case EPISODE -> buildEpisodeDetails(content, language, region);
        };
    }

    private ContentDetailsDTO buildMovieDetails(Content content, String language, String region) {
        TmdbMovieFullDetails details = tmdbClient.getMovieFullDetails(content.getTmdbId(), language)
                .orElseThrow(this::tmdbUnavailable);

        return new ContentDetailsDTO(
                content.getId(),
                ContentType.MOVIE,
                resolveMovieTitle(details, region),
                details.overview(),
                details.posterPath(),
                details.backdropPath(),
                parseDate(details.releaseDate()),
                details.runtime(),
                null,
                genreNames(details.genres()),
                countryCodes(details.productionCountries()),
                castFromCredits(details.credits()),
                null,
                null,
                watchProviders(details.watchProviders(), region),
                null,
                null,
                null);
    }

    private ContentDetailsDTO buildSeriesDetails(Content content, String language, String region) {
        TmdbTvFullDetails details = tmdbClient.getTvFullDetails(content.getTmdbId(), language)
                .orElseThrow(this::tmdbUnavailable);
        List<TmdbSeasonFullDetails> allSeasons = fetchAllSeasonsInParallel(content.getTmdbId(), details.seasons(), language);
        List<Integer> episodeRuntimes = episodeRuntimes(allSeasons);

        return new ContentDetailsDTO(
                content.getId(),
                ContentType.SERIES,
                resolveTvTitle(details, region),
                details.overview(),
                details.posterPath(),
                details.backdropPath(),
                parseDate(details.firstAirDate()),
                averageRuntime(episodeRuntimes),
                totalRuntimeMinutes(episodeRuntimes),
                genreNames(details.genres()),
                countryCodes(details.productionCountries()),
                castFromAggregateCredits(details.aggregateCredits()),
                null,
                creators(details.createdBy()),
                watchProviders(details.watchProviders(), region),
                seasonSummaries(details.seasons()),
                null,
                recentlyAiredEpisodes(allSeasons));
    }

    private ContentDetailsDTO buildSeasonDetails(Content content, String language, String region) {
        TmdbSeasonFullDetails season = tmdbClient
                .getSeasonFullDetails(content.getSeriesTmdbId(), content.getSeasonNumber(), language)
                .orElseThrow(this::tmdbUnavailable);
        TmdbTvFullDetails series = tmdbClient.getTvFullDetails(content.getSeriesTmdbId(), language)
                .orElseThrow(this::tmdbUnavailable);

        return new ContentDetailsDTO(
                content.getId(),
                ContentType.SEASON,
                season.name(),
                season.overview(),
                season.posterPath(),
                null,
                parseDate(season.airDate()),
                null,
                null,
                genreNames(series.genres()),
                countryCodes(series.productionCountries()),
                castFromAggregateCredits(series.aggregateCredits()),
                null,
                null,
                watchProviders(season.watchProviders(), region),
                null,
                episodeSummaries(season.seasonNumber(), season.episodes()),
                null);
    }

    private ContentDetailsDTO buildEpisodeDetails(Content content, String language, String region) {
        TmdbEpisodeFullDetails episode = tmdbClient.getEpisodeFullDetails(
                        content.getSeriesTmdbId(), content.getSeasonNumber(), content.getEpisodeNumber(), language)
                .orElseThrow(this::tmdbUnavailable);
        TmdbTvFullDetails series = tmdbClient.getTvFullDetails(content.getSeriesTmdbId(), language)
                .orElseThrow(this::tmdbUnavailable);

        return new ContentDetailsDTO(
                content.getId(),
                ContentType.EPISODE,
                episode.name(),
                episode.overview(),
                episode.stillPath(),
                null,
                parseDate(episode.airDate()),
                episode.runtime(),
                null,
                genreNames(series.genres()),
                countryCodes(series.productionCountries()),
                castFromAggregateCredits(series.aggregateCredits()),
                guestStars(episode.guestStars()),
                null,
                List.of(),
                null,
                null,
                null);
    }

    private String resolveMovieTitle(TmdbMovieFullDetails details, String region) {
        if (details.title() != null && !details.title().isBlank()) {
            return details.title();
        }
        Optional<String> alternative = alternativeTitleForRegion(
                details.alternativeTitles() == null ? null : details.alternativeTitles().titles(), region);
        return alternative.orElse(details.originalTitle());
    }

    private String resolveTvTitle(TmdbTvFullDetails details, String region) {
        if (details.name() != null && !details.name().isBlank()) {
            return details.name();
        }
        Optional<String> alternative = alternativeTitleForRegion(
                details.alternativeTitles() == null ? null : details.alternativeTitles().results(), region);
        return alternative.orElse(details.originalName());
    }

    private Optional<String> alternativeTitleForRegion(List<TmdbAlternativeTitleEntry> entries, String region) {
        if (entries == null) {
            return Optional.empty();
        }
        return entries.stream()
                .filter(entry -> region.equals(entry.isoCode()))
                .map(TmdbAlternativeTitleEntry::title)
                .findFirst();
    }

    private List<String> genreNames(List<TmdbGenre> genres) {
        if (genres == null) {
            return List.of();
        }
        return genres.stream().map(TmdbGenre::name).toList();
    }

    private List<String> countryCodes(List<TmdbProductionCountry> countries) {
        if (countries == null) {
            return List.of();
        }
        return countries.stream().map(TmdbProductionCountry::isoCode).toList();
    }

    private List<CastMemberDTO> castFromCredits(TmdbCredits credits) {
        if (credits == null || credits.cast() == null) {
            return List.of();
        }
        return credits.cast().stream()
                .map(member -> new CastMemberDTO(member.name(), member.character(), member.profilePath()))
                .toList();
    }

    private List<CastMemberDTO> castFromAggregateCredits(TmdbAggregateCredits credits) {
        if (credits == null || credits.cast() == null) {
            return List.of();
        }
        return credits.cast().stream()
                .map(this::toAggregateCastMemberDto)
                .toList();
    }

    private CastMemberDTO toAggregateCastMemberDto(TmdbAggregateCastMember member) {
        String character = member.roles() == null || member.roles().isEmpty()
                ? null
                : member.roles().get(0).character();
        return new CastMemberDTO(member.name(), character, member.profilePath());
    }

    private List<CastMemberDTO> guestStars(List<TmdbGuestStar> guestStars) {
        if (guestStars == null) {
            return List.of();
        }
        return guestStars.stream()
                .map(guest -> new CastMemberDTO(guest.name(), guest.character(), guest.profilePath()))
                .toList();
    }

    private List<CreatorDTO> creators(List<TmdbCreator> creators) {
        if (creators == null) {
            return List.of();
        }
        return creators.stream()
                .map(creator -> new CreatorDTO(creator.name(), creator.profilePath()))
                .toList();
    }

    private List<SeasonSummaryDTO> seasonSummaries(List<TmdbSeasonSummary> seasons) {
        if (seasons == null) {
            return List.of();
        }
        return seasons.stream()
                .map(season -> new SeasonSummaryDTO(
                        season.seasonNumber(), season.name(), season.posterPath(),
                        parseDate(season.airDate()), season.episodeCount()))
                .toList();
    }

    private List<EpisodeSummaryDTO> episodeSummaries(Integer seasonNumber, List<TmdbEpisodeSummary> episodes) {
        if (episodes == null) {
            return List.of();
        }
        return episodes.stream()
                .map(episode -> toEpisodeSummaryDto(seasonNumber, episode))
                .toList();
    }

    private EpisodeSummaryDTO toEpisodeSummaryDto(Integer seasonNumber, TmdbEpisodeSummary episode) {
        return new EpisodeSummaryDTO(
                seasonNumber, episode.episodeNumber(), episode.name(),
                parseDate(episode.airDate()), episode.runtime(), episode.stillPath());
    }

    private List<TmdbSeasonFullDetails> fetchAllSeasonsInParallel(
            String seriesTmdbId, List<TmdbSeasonSummary> seasons, String language) {
        if (seasons == null || seasons.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<Optional<TmdbSeasonFullDetails>>> futures = seasons.stream()
                .map(season -> CompletableFuture.supplyAsync(
                        () -> tmdbClient.getSeasonFullDetails(seriesTmdbId, season.seasonNumber(), language),
                        tmdbSeasonFetchExecutor))
                .toList();
        return futures.stream()
                .map(CompletableFuture::join)
                .flatMap(Optional::stream)
                .toList();
    }

    private List<Integer> episodeRuntimes(List<TmdbSeasonFullDetails> seasons) {
        return seasons.stream()
                .filter(Objects::nonNull)
                .map(TmdbSeasonFullDetails::episodes)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(TmdbEpisodeSummary::runtime)
                .filter(Objects::nonNull)
                .toList();
    }

    private Integer totalRuntimeMinutes(List<Integer> episodeRuntimes) {
        if (episodeRuntimes.isEmpty()) {
            return null;
        }
        return episodeRuntimes.stream().mapToInt(Integer::intValue).sum();
    }

    private List<EpisodeSummaryDTO> recentlyAiredEpisodes(List<TmdbSeasonFullDetails> seasons) {
        LocalDate today = LocalDate.now();
        return seasons.stream()
                .filter(season -> season.episodes() != null)
                .flatMap(season -> season.episodes().stream()
                        .map(episode -> toEpisodeSummaryDto(season.seasonNumber(), episode)))
                .filter(episode -> episode.airDate() != null && !episode.airDate().isAfter(today))
                .sorted(Comparator.comparing(EpisodeSummaryDTO::airDate).reversed())
                .limit(RECENT_EPISODES_LIMIT)
                .toList();
    }

    private List<WatchProviderDTO> watchProviders(TmdbWatchProviders watchProviders, String region) {
        if (watchProviders == null || watchProviders.results() == null) {
            return List.of();
        }
        TmdbRegionProviders regionProviders = watchProviders.results().get(region);
        if (regionProviders == null) {
            return List.of();
        }

        List<WatchProviderDTO> result = new ArrayList<>();
        appendProviders(result, regionProviders.flatrate(), "flatrate");
        appendProviders(result, regionProviders.rent(), "rent");
        appendProviders(result, regionProviders.buy(), "buy");
        return result;
    }

    private void appendProviders(List<WatchProviderDTO> target, List<TmdbProvider> providers, String type) {
        if (providers == null) {
            return;
        }
        providers.forEach(provider ->
                target.add(new WatchProviderDTO(provider.providerName(), provider.logoPath(), type)));
    }

    private Integer averageRuntime(List<Integer> episodeRunTime) {
        if (episodeRunTime == null || episodeRunTime.isEmpty()) {
            return null;
        }
        return (int) Math.round(episodeRunTime.stream().mapToInt(Integer::intValue).average().orElse(0));
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private TmdbUnavailableException tmdbUnavailable() {
        return new TmdbUnavailableException("TMDB is currently unavailable");
    }
}
