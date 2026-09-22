package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentStateDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentSchedule;
import com.watchwise.watchwise_api.content.service.ContentScheduleKey;
import com.watchwise.watchwise_api.content.service.ContentScheduleLookup;
import com.watchwise.watchwise_api.content.service.ContentScheduleReader;
import com.watchwise.watchwise_api.content.service.ContentStateResolver;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.WatchedEpisodeCoordinate;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import com.watchwise.watchwise_api.userlist.service.UserListContentStateResult;
import com.watchwise.watchwise_api.userlist.service.UserListContentStateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UserListContentStateServiceImpl implements UserListContentStateService {

    private final UserRepository userRepository;
    private final DiaryEntryRepository diaryEntryRepository;
    private final ContentScheduleReader contentScheduleReader;
    private final ContentStateResolver contentStateResolver;
    private final Clock clock;

    @Autowired
    public UserListContentStateServiceImpl(
            UserRepository userRepository,
            DiaryEntryRepository diaryEntryRepository,
            ContentScheduleReader contentScheduleReader,
            Clock clock) {
        this(userRepository, diaryEntryRepository, contentScheduleReader, new ContentStateResolver(), clock);
    }

    public UserListContentStateServiceImpl(
            UserRepository userRepository,
            DiaryEntryRepository diaryEntryRepository,
            ContentScheduleReader contentScheduleReader,
            ContentStateResolver contentStateResolver,
            Clock clock) {
        this.userRepository = userRepository;
        this.diaryEntryRepository = diaryEntryRepository;
        this.contentScheduleReader = contentScheduleReader;
        this.contentStateResolver = contentStateResolver;
        this.clock = clock;
    }

    @Override
    public UserListContentStateResult resolve(UUID viewerId, Collection<UserListItem> items) {
        User viewer = userRepository.findById(viewerId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        List<UserListItem> contentItems = items.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getContent() != null)
                .toList();

        Set<UUID> contentIds = contentItems.stream()
                .map(UserListItem::getContent)
                .map(Content::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> seriesTmdbIds = contentItems.stream()
                .map(UserListItem::getContent)
                .filter(content -> content.getType() == ContentType.SERIES
                        || content.getType() == ContentType.SEASON
                        || content.getType() == ContentType.EPISODE)
                .map(this::seriesTmdbIdFor)
                .filter(this::hasText)
                .collect(Collectors.toUnmodifiableSet());
        Set<SeasonKey> seasonKeys = contentItems.stream()
                .map(UserListItem::getContent)
                .filter(content -> content.getType() == ContentType.SEASON || content.getType() == ContentType.EPISODE)
                .filter(content -> hasText(content.getSeriesTmdbId())
                        && content.getSeasonNumber() != null && content.getSeasonNumber() > 0)
                .map(content -> new SeasonKey(content.getSeriesTmdbId(), content.getSeasonNumber()))
                .collect(Collectors.toUnmodifiableSet());

        Set<UUID> watchedDirectContentIds = contentIds.isEmpty()
                ? Set.of()
                : immutableSet(diaryEntryRepository.findWatchedDirectContentIds(viewerId, contentIds));
        Set<WatchedEpisodeCoordinate> watchedEpisodeCoordinates = seriesTmdbIds.isEmpty()
                ? Set.of()
                : immutableSet(diaryEntryRepository.findWatchedEpisodeCoordinates(viewerId, seriesTmdbIds));

        Map<String, ContentScheduleLookup> movieLookups = readMovieSchedules(
                contentItems, viewer.getPreferredRegion(), viewer.getPreferredLanguage());
        Map<String, ContentScheduleLookup> seriesLookups = readSeriesSchedules(
                contentItems, viewer.getPreferredRegion(), viewer.getPreferredLanguage());
        Map<SeasonKey, ContentScheduleLookup> seasonLookups = readSeasonSchedules(
                seasonKeys, viewer.getPreferredRegion(), viewer.getPreferredLanguage());

        Map<UUID, ContentStateDTO> stateByItemId = new LinkedHashMap<>();
        Map<UUID, Integer> contentCountByListId = new LinkedHashMap<>();
        Map<UUID, Integer> watchedCountByListId = new LinkedHashMap<>();
        Set<UUID> listIds = new LinkedHashSet<>();

        for (UserListItem item : items) {
            if (item == null || item.getUserList() == null || item.getUserList().getId() == null) {
                continue;
            }
            UUID listId = item.getUserList().getId();
            listIds.add(listId);
            Content content = item.getContent();
            if (content == null) {
                continue;
            }

            contentCountByListId.merge(listId, 1, Integer::sum);
            ContentSchedule schedule = scheduleFor(
                    content,
                    movieLookups,
                    seriesLookups,
                    seasonLookups);
            ContentStateDTO state = contentStateResolver.resolve(
                    content, schedule, watchedDirectContentIds, watchedEpisodeCoordinates, clock);
            if (item.getId() != null) {
                stateByItemId.put(item.getId(), state);
            }
            if (state.watchStatus() == com.watchwise.watchwise_api.content.dto.WatchStatus.WATCHED) {
                watchedCountByListId.merge(listId, 1, Integer::sum);
            }
        }

        Map<UUID, Double> watchedPercentageByListId = new LinkedHashMap<>();
        for (UUID listId : listIds) {
            int contentCount = contentCountByListId.getOrDefault(listId, 0);
            int watchedCount = watchedCountByListId.getOrDefault(listId, 0);
            watchedPercentageByListId.put(
                    listId,
                    contentCount == 0 ? 0.0 : watchedCount * 100.0 / contentCount);
        }

        return new UserListContentStateResult(
                Collections.unmodifiableMap(stateByItemId),
                Collections.unmodifiableMap(watchedPercentageByListId));
    }

    private Map<String, ContentScheduleLookup> readMovieSchedules(
            Collection<UserListItem> contentItems, String region, String language) {
        Set<String> tmdbIds = contentItems.stream()
                .map(UserListItem::getContent)
                .filter(content -> content.getType() == ContentType.MOVIE)
                .map(Content::getTmdbId)
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, ContentScheduleLookup> lookups = new LinkedHashMap<>();
        for (String tmdbId : tmdbIds) {
            lookups.put(tmdbId, contentScheduleReader.readMovie(tmdbId, region, language));
        }
        return lookups;
    }

    private Map<String, ContentScheduleLookup> readSeriesSchedules(
            Collection<UserListItem> contentItems, String region, String language) {
        Set<String> tmdbIds = contentItems.stream()
                .map(UserListItem::getContent)
                .filter(content -> content.getType() == ContentType.SERIES)
                .map(Content::getTmdbId)
                .filter(this::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, ContentScheduleLookup> lookups = new LinkedHashMap<>();
        for (String tmdbId : tmdbIds) {
            lookups.put(tmdbId, contentScheduleReader.readSeries(tmdbId, region, language));
        }
        return lookups;
    }

    private Map<SeasonKey, ContentScheduleLookup> readSeasonSchedules(
            Set<SeasonKey> seasonKeys, String region, String language) {
        Map<SeasonKey, ContentScheduleLookup> lookups = new LinkedHashMap<>();
        for (SeasonKey key : seasonKeys) {
            lookups.put(key, contentScheduleReader.readSeason(
                    key.seriesTmdbId(), key.seasonNumber(), region, language));
        }
        return lookups;
    }

    private ContentSchedule scheduleFor(
            Content content,
            Map<String, ContentScheduleLookup> movieLookups,
            Map<String, ContentScheduleLookup> seriesLookups,
            Map<SeasonKey, ContentScheduleLookup> seasonLookups) {
        ContentScheduleLookup lookup = switch (content.getType()) {
            case MOVIE -> movieLookups.get(content.getTmdbId());
            case SERIES -> seriesLookups.get(content.getTmdbId());
            case SEASON, EPISODE -> seasonLookups.get(new SeasonKey(
                    content.getSeriesTmdbId(), content.getSeasonNumber()));
        };

        if (lookup instanceof ContentScheduleLookup.Found found) {
            return content.getType() == ContentType.EPISODE
                    ? episodeScheduleFor(content, found.schedule())
                    : found.schedule();
        }
        return unknownScheduleFor(content);
    }

    private ContentSchedule episodeScheduleFor(Content episode, ContentSchedule seasonSchedule) {
        return seasonSchedule.episodes().stream()
                .filter(candidate -> candidate.seriesTmdbId() == null
                        || Objects.equals(episode.getSeriesTmdbId(), candidate.seriesTmdbId()))
                .filter(candidate -> Objects.equals(episode.getSeasonNumber(), candidate.seasonNumber()))
                .filter(candidate -> Objects.equals(episode.getEpisodeNumber(), candidate.episodeNumber()))
                .findFirst()
                .map(candidate -> new ContentSchedule(
                        seasonSchedule.key(),
                        candidate.releaseDate(),
                        seasonSchedule.externalStatus(),
                        List.of(candidate),
                        seasonSchedule.complete(),
                        seasonSchedule.releaseDateLookupUnavailable(),
                        seasonSchedule.title(),
                        seasonSchedule.posterPath(),
                        seasonSchedule.expectedEpisodeCountsBySeason()))
                .orElseGet(() -> unknownScheduleFor(episode));
    }

    private ContentSchedule unknownScheduleFor(Content content) {
        ContentScheduleKey key = switch (content.getType()) {
            case MOVIE -> ContentScheduleKey.movie(content.getTmdbId());
            case SERIES -> ContentScheduleKey.series(content.getTmdbId());
            case SEASON, EPISODE -> ContentScheduleKey.season(
                    content.getSeriesTmdbId(), content.getSeasonNumber());
        };
        return new ContentSchedule(key, null, null, List.of(), false, false);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String seriesTmdbIdFor(Content content) {
        return content.getType() == ContentType.SERIES
                ? content.getTmdbId()
                : content.getSeriesTmdbId();
    }

    private <T> Set<T> immutableSet(Collection<T> values) {
        return values == null || values.isEmpty() ? Set.of() : Set.copyOf(values);
    }

    private record SeasonKey(String seriesTmdbId, Integer seasonNumber) {
    }
}
