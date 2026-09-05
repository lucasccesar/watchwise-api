package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCastMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCredits;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCrewJob;
import com.watchwise.watchwise_api.common.tmdb.TmdbAggregateCrewMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbAlternativeTitleEntry;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbCreator;
import com.watchwise.watchwise_api.common.tmdb.TmdbCredits;
import com.watchwise.watchwise_api.common.tmdb.TmdbCrewMember;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbGenre;
import com.watchwise.watchwise_api.common.tmdb.TmdbGuestStar;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbProductionCompany;
import com.watchwise.watchwise_api.common.tmdb.TmdbProductionCountry;
import com.watchwise.watchwise_api.common.tmdb.TmdbProvider;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionProviders;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbVideo;
import com.watchwise.watchwise_api.common.tmdb.TmdbVideos;
import com.watchwise.watchwise_api.common.tmdb.TmdbWatchProviders;
import com.watchwise.watchwise_api.content.dto.CastMemberDTO;
import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.dto.CreatorDTO;
import com.watchwise.watchwise_api.content.dto.CrewMemberDTO;
import com.watchwise.watchwise_api.content.dto.EpisodeSummaryDTO;
import com.watchwise.watchwise_api.content.dto.ProductionCompanyDTO;
import com.watchwise.watchwise_api.content.dto.SeasonSummaryDTO;
import com.watchwise.watchwise_api.content.dto.VideoDTO;
import com.watchwise.watchwise_api.content.dto.WatchProviderDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentDetailsService;
import com.watchwise.watchwise_api.notification.service.ContentTrackingService;
import com.watchwise.watchwise_api.notification.tracking.ContentChangeDetector;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContentDetailsServiceImpl implements ContentDetailsService {

    static final int MAX_BATCH_IDS = 100;
    private static final int RECENT_EPISODES_LIMIT = 3;
    private static final int RECENT_SEASONS_LIMIT_WHEN_FROZEN = 2;
    private static final Set<String> ALLOWED_CREW_JOBS = Set.of(
            "Director", "Screenplay", "Executive Producer", "Production Manager",
            "First Assistant Director", "Director of Photography", "Supervising Art Director");
    private static final String YOUTUBE_SITE = "YouTube";
    private static final String YOUTUBE_WATCH_URL_PREFIX = "https://www.youtube.com/watch?v=";

    private final ContentRepository contentRepository;
    private final UserRepository userRepository;
    private final TmdbClient tmdbClient;
    private final ExecutorService tmdbSeasonFetchExecutor;
    private final ContentTrackingService contentTrackingService;

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
                .toOptional().orElseThrow(this::tmdbUnavailable);

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
                null,
                null,
                genreNames(details.genres()),
                countryCodes(details.productionCountries()),
                castFromCredits(details.credits()),
                null,
                null,
                watchProviders(details.watchProviders(), region),
                null,
                null,
                null,
                nullIfZero(details.budget()),
                nullIfZero(details.revenue()),
                productionCompanies(details.productionCompanies()),
                crewFromCredits(details.credits()),
                videos(details.videos()));
    }

    private ContentDetailsDTO buildSeriesDetails(Content content, String language, String region) {
        TmdbTvFullDetails details = tmdbClient.getTvFullDetails(content.getTmdbId(), language)
                .toOptional().orElseThrow(this::tmdbUnavailable);

        List<TmdbSeasonFullDetails> allSeasons;
        Integer averageRuntimeMinutes;
        Integer totalRuntimeMinutes;

        if (isTerminalSeriesStatus(details.status()) && content.getTotalRuntimeMinutes() != null) {
            allSeasons = fetchAllSeasonsInParallel(
                    content.getTmdbId(), latestSeasons(details.seasons(), RECENT_SEASONS_LIMIT_WHEN_FROZEN), language);
            totalRuntimeMinutes = content.getTotalRuntimeMinutes();
            averageRuntimeMinutes = averageFromStored(content.getTotalRuntimeMinutes(), content.getRuntimeMinutesEpisodeCount());
        } else {
            allSeasons = fetchAllSeasonsInParallel(content.getTmdbId(), details.seasons(), language);
            List<Integer> episodeRuntimes = episodeRuntimes(allSeasons);
            totalRuntimeMinutes = totalRuntimeMinutes(episodeRuntimes);
            averageRuntimeMinutes = averageRuntime(episodeRuntimes);
            persistRuntimeAggregate(content, totalRuntimeMinutes, episodeRuntimes.size());
            contentTrackingService.reactivateAfterRevival(content, details.status());
        }

        return new ContentDetailsDTO(
                content.getId(),
                ContentType.SERIES,
                resolveTvTitle(details, region),
                details.overview(),
                details.posterPath(),
                details.backdropPath(),
                parseDate(details.firstAirDate()),
                averageRuntimeMinutes,
                totalRuntimeMinutes,
                details.numberOfSeasons(),
                details.numberOfEpisodes(),
                genreNames(details.genres()),
                countryCodes(details.productionCountries()),
                castFromAggregateCredits(details.aggregateCredits()),
                null,
                creators(details.createdBy()),
                watchProviders(details.watchProviders(), region),
                seasonSummaries(details.seasons(), allSeasons),
                null,
                recentlyAiredEpisodes(allSeasons),
                null,
                null,
                productionCompanies(details.productionCompanies()),
                crewFromAggregateCredits(details.aggregateCredits()),
                videos(details.videos()));
    }

    private ContentDetailsDTO buildSeasonDetails(Content content, String language, String region) {
        TmdbSeasonFullDetails season = tmdbClient
                .getSeasonFullDetails(content.getSeriesTmdbId(), content.getSeasonNumber(), language)
                .toOptional().orElseThrow(this::tmdbUnavailable);
        TmdbTvFullDetails series = tmdbClient.getTvFullDetails(content.getSeriesTmdbId(), language)
                .toOptional().orElseThrow(this::tmdbUnavailable);
        List<Integer> episodeRuntimes = runtimesOf(season.episodes());

        return new ContentDetailsDTO(
                content.getId(),
                ContentType.SEASON,
                season.name(),
                season.overview(),
                season.posterPath(),
                null,
                parseDate(season.airDate()),
                averageRuntime(episodeRuntimes),
                totalRuntimeMinutes(episodeRuntimes),
                null,
                numberOfEpisodes(season.episodes()),
                genreNames(series.genres()),
                countryCodes(series.productionCountries()),
                castFromAggregateCredits(season.aggregateCredits()),
                seasonGuestStars(season.episodes()),
                creators(series.createdBy()),
                watchProviders(season.watchProviders(), region),
                null,
                episodeSummaries(season.seasonNumber(), season.episodes()),
                null,
                null,
                null,
                productionCompanies(series.productionCompanies()),
                crewFromAggregateCredits(series.aggregateCredits()),
                videos(series.videos()));
    }

    private ContentDetailsDTO buildEpisodeDetails(Content content, String language, String region) {
        TmdbEpisodeFullDetails episode = tmdbClient.getEpisodeFullDetails(
                        content.getSeriesTmdbId(), content.getSeasonNumber(), content.getEpisodeNumber(), language)
                .toOptional().orElseThrow(this::tmdbUnavailable);
        TmdbTvFullDetails series = tmdbClient.getTvFullDetails(content.getSeriesTmdbId(), language)
                .toOptional().orElseThrow(this::tmdbUnavailable);

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
                null,
                null,
                genreNames(series.genres()),
                countryCodes(series.productionCountries()),
                castFromAggregateCredits(series.aggregateCredits()),
                guestStars(episode.guestStars()),
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                productionCompanies(series.productionCompanies()),
                crewFromAggregateCredits(series.aggregateCredits()),
                videos(series.videos()));
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
                .map(member -> new CastMemberDTO(member.id(), member.name(), member.character(), member.profilePath(), null))
                .toList();
    }

    private List<CastMemberDTO> castFromAggregateCredits(TmdbAggregateCredits credits) {
        if (credits == null || credits.cast() == null) {
            return List.of();
        }
        return credits.cast().stream()
                .map(member -> toAggregateCastMemberDto(member, member.totalEpisodeCount()))
                .toList();
    }

    private CastMemberDTO toAggregateCastMemberDto(TmdbAggregateCastMember member, Integer episodeCount) {
        String character = member.roles() == null || member.roles().isEmpty()
                ? null
                : member.roles().get(0).character();
        return new CastMemberDTO(member.id(), member.name(), character, member.profilePath(), episodeCount);
    }

    private List<CastMemberDTO> guestStars(List<TmdbGuestStar> guestStars) {
        if (guestStars == null) {
            return List.of();
        }
        return guestStars.stream()
                .map(guest -> new CastMemberDTO(guest.id(), guest.name(), guest.character(), guest.profilePath(), null))
                .toList();
    }

    private List<CastMemberDTO> seasonGuestStars(List<TmdbEpisodeSummary> episodes) {
        if (episodes == null) {
            return List.of();
        }
        Map<Integer, TmdbGuestStar> firstAppearanceById = new LinkedHashMap<>();
        Map<Integer, Integer> episodeCountById = new HashMap<>();
        for (TmdbEpisodeSummary episode : episodes) {
            if (episode.guestStars() == null) {
                continue;
            }
            for (TmdbGuestStar guest : episode.guestStars()) {
                firstAppearanceById.putIfAbsent(guest.id(), guest);
                episodeCountById.merge(guest.id(), 1, Integer::sum);
            }
        }
        return firstAppearanceById.values().stream()
                .map(guest -> new CastMemberDTO(
                        guest.id(), guest.name(), guest.character(), guest.profilePath(), episodeCountById.get(guest.id())))
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

    private List<CrewMemberDTO> crewFromCredits(TmdbCredits credits) {
        if (credits == null || credits.crew() == null) {
            return List.of();
        }
        Map<Integer, CrewAccumulator> accumulators = new LinkedHashMap<>();
        for (TmdbCrewMember member : credits.crew()) {
            if (member.job() == null || !ALLOWED_CREW_JOBS.contains(member.job())) {
                continue;
            }
            accumulators.computeIfAbsent(member.id(), id -> new CrewAccumulator(id, member.name(), member.profilePath()))
                    .jobs.add(member.job());
        }
        return accumulators.values().stream()
                .map(accumulator -> new CrewMemberDTO(accumulator.id, accumulator.name, accumulator.profilePath, accumulator.jobs))
                .toList();
    }

    private List<CrewMemberDTO> crewFromAggregateCredits(TmdbAggregateCredits credits) {
        if (credits == null || credits.crew() == null) {
            return List.of();
        }
        List<CrewMemberDTO> result = new ArrayList<>();
        for (TmdbAggregateCrewMember member : credits.crew()) {
            List<String> matchingJobs = member.jobs() == null
                    ? List.of()
                    : member.jobs().stream().map(TmdbAggregateCrewJob::job)
                            .filter(Objects::nonNull).filter(ALLOWED_CREW_JOBS::contains).toList();
            if (!matchingJobs.isEmpty()) {
                result.add(new CrewMemberDTO(member.id(), member.name(), member.profilePath(), matchingJobs));
            }
        }
        return result;
    }

    private List<ProductionCompanyDTO> productionCompanies(List<TmdbProductionCompany> companies) {
        if (companies == null) {
            return List.of();
        }
        return companies.stream()
                .map(company -> new ProductionCompanyDTO(company.id(), company.name(), company.logoPath(), company.originCountry()))
                .toList();
    }

    private List<VideoDTO> videos(TmdbVideos videos) {
        if (videos == null || videos.results() == null) {
            return List.of();
        }
        return videos.results().stream()
                .filter(video -> YOUTUBE_SITE.equalsIgnoreCase(video.site()))
                .map(video -> new VideoDTO(
                        video.key(), video.name(), video.site(), video.type(), video.official(),
                        video.isoCode639_1(), parseInstant(video.publishedAt()), YOUTUBE_WATCH_URL_PREFIX + video.key()))
                .toList();
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Long nullIfZero(Long value) {
        return value == null || value == 0L ? null : value;
    }

    private List<SeasonSummaryDTO> seasonSummaries(List<TmdbSeasonSummary> seasons, List<TmdbSeasonFullDetails> allSeasons) {
        if (seasons == null) {
            return List.of();
        }
        Map<Integer, TmdbSeasonFullDetails> fullDetailsBySeasonNumber = allSeasons.stream()
                .filter(season -> season.seasonNumber() != null)
                .collect(Collectors.toMap(TmdbSeasonFullDetails::seasonNumber, Function.identity(), (a, b) -> a));
        LocalDate today = LocalDate.now();
        return seasons.stream()
                .map(season -> new SeasonSummaryDTO(
                        season.seasonNumber(), season.name(), season.posterPath(),
                        parseDate(season.airDate()), season.episodeCount(),
                        airedEpisodeCount(fullDetailsBySeasonNumber.get(season.seasonNumber()), today)))
                .toList();
    }

    private Integer airedEpisodeCount(TmdbSeasonFullDetails season, LocalDate today) {
        if (season == null || season.episodes() == null) {
            return null;
        }
        return (int) season.episodes().stream()
                .map(TmdbEpisodeSummary::airDate)
                .map(this::parseDate)
                .filter(airDate -> airDate != null && !airDate.isAfter(today))
                .count();
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
                .filter(season -> !Integer.valueOf(0).equals(season.seasonNumber()))
                .map(season -> CompletableFuture.supplyAsync(
                        () -> tmdbClient.getSeasonFullDetails(seriesTmdbId, season.seasonNumber(), language).toOptional(),
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
                .flatMap(season -> runtimesOf(season.episodes()).stream())
                .toList();
    }

    private List<Integer> runtimesOf(List<TmdbEpisodeSummary> episodes) {
        if (episodes == null) {
            return List.of();
        }
        return episodes.stream()
                .map(TmdbEpisodeSummary::runtime)
                .filter(Objects::nonNull)
                .toList();
    }

    private Integer numberOfEpisodes(List<TmdbEpisodeSummary> episodes) {
        return episodes == null ? null : episodes.size();
    }

    private Integer totalRuntimeMinutes(List<Integer> episodeRuntimes) {
        if (episodeRuntimes.isEmpty()) {
            return null;
        }
        return episodeRuntimes.stream().mapToInt(Integer::intValue).sum();
    }

    private boolean isTerminalSeriesStatus(String status) {
        return ContentChangeDetector.ENDED_STATUS.equals(status) || ContentChangeDetector.CANCELED_STATUS.equals(status);
    }

    private List<TmdbSeasonSummary> latestSeasons(List<TmdbSeasonSummary> seasons, int limit) {
        if (seasons == null) {
            return List.of();
        }
        return seasons.stream()
                .filter(season -> season.seasonNumber() != null)
                .sorted(Comparator.comparing(TmdbSeasonSummary::seasonNumber).reversed())
                .limit(limit)
                .toList();
    }

    private Integer averageFromStored(Integer total, Integer episodeCount) {
        if (total == null || episodeCount == null || episodeCount == 0) {
            return null;
        }
        return (int) Math.round(total / (double) episodeCount);
    }

    private void persistRuntimeAggregate(Content content, Integer total, int episodeCount) {
        if (total == null) {
            return;
        }
        content.setTotalRuntimeMinutes(total);
        content.setRuntimeMinutesEpisodeCount(episodeCount);
        contentRepository.save(content);
    }

    private List<EpisodeSummaryDTO> recentlyAiredEpisodes(List<TmdbSeasonFullDetails> seasons) {
        LocalDate today = LocalDate.now();
        return seasons.stream()
                .filter(season -> season.episodes() != null)
                .flatMap(season -> season.episodes().stream()
                        .map(episode -> toEpisodeSummaryDto(season.seasonNumber(), episode)))
                .filter(episode -> episode.airDate() != null && !episode.airDate().isAfter(today))
                .sorted(Comparator.comparing(EpisodeSummaryDTO::airDate)
                        .thenComparing(EpisodeSummaryDTO::seasonNumber)
                        .thenComparing(EpisodeSummaryDTO::episodeNumber)
                        .reversed())
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

    private static final class CrewAccumulator {
        private final Integer id;
        private final String name;
        private final String profilePath;
        private final List<String> jobs = new ArrayList<>();

        private CrewAccumulator(Integer id, String name, String profilePath) {
            this.id = id;
            this.name = name;
            this.profilePath = profilePath;
        }
    }
}
