package com.watchwise.watchwise_api.watchlist.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.transaction.AdvisoryLock;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.contentreleasedatesnapshot.service.ContentReleaseDateSnapshotService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryCreationDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryReorderDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryResponseDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistPageResponseDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistAggregateDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistCardDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistSort;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistStatus;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistViewResponseDTO;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import com.watchwise.watchwise_api.watchlist.mapper.WatchlistEntryMapper;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import com.watchwise.watchwise_api.watchlist.service.WatchlistEntryService;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressReadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WatchlistEntryServiceImpl implements WatchlistEntryService {

    private final WatchlistEntryRepository watchlistEntryRepository;
    private final UserRepository userRepository;
    private final ContentRepository contentRepository;
    private final ContentService contentService;
    private final FollowerRepository followerRepository;
    private final WatchlistEntryMapper watchlistEntryMapper;
    private final PageRequestFactory pageRequestFactory;
    private final AdvisoryLock advisoryLock;
    private final ContentReleaseDateSnapshotService releaseDateSnapshotService;
    private final ContentCardAssembler contentCardAssembler;
    private final SeriesProgressReadRepository seriesProgressReadRepository;

    @Value("${app.watchlist.view.max-materialized-sort-candidates:1000}")
    private int maxMaterializedSortCandidates = 1000;

    static final int POSITION_PARK_OFFSET = 1_000_000_000;
    private static final int VIEW_DEFAULT_PAGE_SIZE = 30;
    private static final Set<ContentCardFieldSet> VIEW_CARD_FIELDS = Set.of(
            ContentCardFieldSet.BASIC_METADATA,
            ContentCardFieldSet.STATS,
            ContentCardFieldSet.SOCIAL_METADATA);

    @Override
    public WatchlistPageResponseDTO getWatchlist(
            UUID viewerId, UUID userId, ContentType type, Integer pageNumber, Integer pageSize) {
        validateType(type);

        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanViewWatchlist(viewerId, userId, target);

        PageRequest pageRequest = pageRequestFactory.build(pageNumber, pageSize);
        Page<WatchlistEntry> entries = type == null
                ? watchlistEntryRepository.findByUserIdOrderByPositionAsc(userId, pageRequest)
                : watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(userId, type, pageRequest);
        List<WatchlistEntry> entriesForDateResolution = entriesForDateResolution(userId, type, entries);
        var dates = releaseDateSnapshotService.resolve(target, entriesForDateResolution).releaseDates();
        Page<WatchlistEntryResponseDTO> mapped = entries.map(entry -> withReleaseDate(
                watchlistEntryMapper.watchlistEntryToResponseDto(entry), dates.get(entry.getId())));
        long upcomingCount = releaseDateSnapshotService.countUpcoming(
                userId, type, target.getPreferredRegion(), LocalDate.now());
        return WatchlistPageResponseDTO.of(mapped, upcomingCount);
    }

    @Override
    public WatchlistViewResponseDTO getWatchlistView(
            UUID viewerId,
            UUID userId,
            ContentType type,
            String genre,
            WatchlistStatus status,
            WatchlistSort sort,
            String direction,
            Integer pageNumber,
            Integer pageSize) {
        validateType(type);

        User target = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        assertCanViewWatchlist(viewerId, userId, target);

        String normalizedGenre = normalizeGenre(genre);
        WatchlistStatus effectiveStatus = status == null ? WatchlistStatus.ALL : status;
        WatchlistSort effectiveSort = sort == null ? WatchlistSort.DATE_ADDED : sort;
        boolean descending = resolveDescending(direction);
        PageRequest pageRequest = viewPageRequest(pageNumber, pageSize);

        List<WatchlistEntry> candidates = watchlistEntryRepository.findByUserIdWithContentForView(userId).stream()
                .filter(entry -> entry != null && entry.getContent() != null)
                .filter(entry -> type == null || entry.getType() == type)
                .filter(entry -> matchesGenre(entry.getContent(), normalizedGenre))
                .toList();

        if (requiresMaterializedSort(effectiveSort)
                && candidates.size() > maxMaterializedSortCandidates) {
            throw new BadRequestException(
                    "Sort " + effectiveSort
                            + " supports at most " + maxMaterializedSortCandidates
                            + " candidates; restrict type, genre or status");
        }

        Map<UUID, LocalDate> releaseDates = candidates.isEmpty()
                ? Map.of()
                : safeReleaseDates(target, candidates);
        Set<String> inProgressSeriesIds = inProgressSeriesIds(userId, candidates);

        List<ViewCandidate> filtered = candidates.stream()
                .map(entry -> toViewCandidate(entry, releaseDates, inProgressSeriesIds))
                .filter(candidate -> effectiveStatus == WatchlistStatus.ALL
                        || candidate.status() == effectiveStatus)
                .toList();

        Map<ContentCoordinate, ContentCardDTO> sortCards = materializedSortCards(
                target, userId, filtered, effectiveSort);
        List<ViewCandidate> sorted = sortCandidates(filtered, effectiveSort, descending, sortCards);
        WatchlistAggregateDTO aggregate = aggregate(filtered);

        Page<ViewCandidate> page = page(sorted, pageRequest);
        Map<ContentCoordinate, ContentCardDTO> pageCards = assemblePageCards(target, userId, page.getContent());
        List<WatchlistCardDTO> cards = page.getContent().stream()
                .map(candidate -> new WatchlistCardDTO(
                        candidate.entry().getId(),
                        pageCards.get(ContentCoordinate.from(candidate.entry().getContent())),
                        candidate.entry().getPosition(),
                        candidate.entry().getCreatedAt(),
                        candidate.releaseDate(),
                        candidate.status()))
                .toList();

        return new WatchlistViewResponseDTO(
                cards,
                page.getNumber() + 1,
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext(),
                aggregate);
    }

    private String normalizeGenre(String genre) {
        if (genre == null) {
            return null;
        }
        String normalized = genre.trim();
        if (normalized.isEmpty()) {
            throw new BadRequestException("genre must not be blank");
        }
        return normalized;
    }

    private boolean resolveDescending(String direction) {
        if (direction == null) {
            return true;
        }
        if (direction.equalsIgnoreCase("desc")) {
            return true;
        }
        if (direction.equalsIgnoreCase("asc")) {
            return false;
        }
        throw new BadRequestException("direction must be one of: ASC, DESC");
    }

    private PageRequest viewPageRequest(Integer pageNumber, Integer pageSize) {
        return pageRequestFactory.build(
                pageNumber == null ? 1 : pageNumber,
                pageSize == null ? VIEW_DEFAULT_PAGE_SIZE : pageSize);
    }

    private boolean matchesGenre(Content content, String genre) {
        return genre == null || content.getGenres() != null && content.getGenres().stream()
                .filter(Objects::nonNull)
                .anyMatch(value -> value.equalsIgnoreCase(genre));
    }

    private Map<UUID, LocalDate> safeReleaseDates(User target, List<WatchlistEntry> candidates) {
        ContentReleaseDateSnapshotService.WatchlistDateResolution resolution =
                releaseDateSnapshotService.resolve(target, candidates);
        return resolution == null || resolution.releaseDates() == null
                ? Map.of()
                : resolution.releaseDates();
    }

    private Set<String> inProgressSeriesIds(UUID ownerId, List<WatchlistEntry> candidates) {
        Set<String> seriesIds = candidates.stream()
                .map(WatchlistEntry::getContent)
                .filter(content -> content.getType() == ContentType.SERIES)
                .map(Content::getTmdbId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (seriesIds.isEmpty()) {
            return Set.of();
        }
        return seriesProgressReadRepository.findProgressByUserIdAndSeriesTmdbIds(ownerId, seriesIds).stream()
                .map(SeriesProgressReadRepository.SeriesProgressCandidate::getSeriesTmdbId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private ViewCandidate toViewCandidate(
            WatchlistEntry entry,
            Map<UUID, LocalDate> releaseDates,
            Set<String> inProgressSeriesIds) {
        LocalDate releaseDate = releaseDates.get(entry.getId());
        WatchlistStatus status = statusFor(entry.getContent(), releaseDate, inProgressSeriesIds);
        return new ViewCandidate(entry, releaseDate, status);
    }

    private WatchlistStatus statusFor(
            Content content, LocalDate releaseDate, Set<String> inProgressSeriesIds) {
        if (releaseDate != null && releaseDate.isAfter(LocalDate.now())) {
            return WatchlistStatus.UPCOMING;
        }
        if (content.getType() == ContentType.SERIES
                && inProgressSeriesIds.contains(content.getTmdbId())) {
            return WatchlistStatus.IN_PROGRESS;
        }
        return WatchlistStatus.NEW;
    }

    private boolean requiresMaterializedSort(WatchlistSort sort) {
        return sort == WatchlistSort.TITLE || sort == WatchlistSort.RATING;
    }

    private Map<ContentCoordinate, ContentCardDTO> materializedSortCards(
            User target, UUID ownerId, List<ViewCandidate> candidates, WatchlistSort sort) {
        if (!requiresMaterializedSort(sort) || candidates.isEmpty()) {
            return Map.of();
        }
        Set<ContentCardFieldSet> fields = sort == WatchlistSort.TITLE
                ? Set.of(ContentCardFieldSet.BASIC_METADATA)
                : Set.of(ContentCardFieldSet.STATS);
        return assembleCards(target, ownerId, candidates, fields);
    }

    private List<ViewCandidate> sortCandidates(
            List<ViewCandidate> candidates,
            WatchlistSort sort,
            boolean descending,
            Map<ContentCoordinate, ContentCardDTO> sortCards) {
        Comparator<ViewCandidate> comparator = (left, right) -> {
            int compared = switch (sort) {
                case DATE_ADDED -> compareNullable(
                        left.entry().getCreatedAt(), right.entry().getCreatedAt(), descending);
                case RELEASE_DATE -> compareNullable(
                        left.releaseDate(), right.releaseDate(), descending);
                case RUNTIME -> compareNullable(
                        runtimeMinutes(left.entry().getContent()), runtimeMinutes(right.entry().getContent()), descending);
                case RATING -> compareNullable(
                        rating(sortCards, left), rating(sortCards, right), descending);
                case TITLE -> compareNullable(
                        title(sortCards, left), title(sortCards, right), descending);
            };
            if (compared != 0) {
                return compared;
            }
            return compareIds(left.entry().getId(), right.entry().getId());
        };
        return candidates.stream().sorted(comparator).toList();
    }

    private <T extends Comparable<? super T>> int compareNullable(T left, T right, boolean descending) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        int compared = left.compareTo(right);
        return descending ? -compared : compared;
    }

    private int compareIds(UUID left, UUID right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return left.compareTo(right);
    }

    private Long runtimeMinutes(Content content) {
        Integer runtime = content.getType() == ContentType.SERIES
                ? content.getTotalRuntimeMinutes()
                : content.getRuntimeMinutes();
        return runtime == null ? null : runtime.longValue();
    }

    private Double rating(Map<ContentCoordinate, ContentCardDTO> cards, ViewCandidate candidate) {
        ContentCardDTO card = cards.get(ContentCoordinate.from(candidate.entry().getContent()));
        return card == null || card.stats() == null ? null : card.stats().averageScore();
    }

    private String title(Map<ContentCoordinate, ContentCardDTO> cards, ViewCandidate candidate) {
        ContentCardDTO card = cards.get(ContentCoordinate.from(candidate.entry().getContent()));
        return card == null || card.title() == null ? null : card.title().toLowerCase(Locale.ROOT);
    }

    private WatchlistAggregateDTO aggregate(List<ViewCandidate> candidates) {
        long movieCount = candidates.stream()
                .filter(candidate -> candidate.entry().getType() == ContentType.MOVIE)
                .count();
        long seriesCount = candidates.stream()
                .filter(candidate -> candidate.entry().getType() == ContentType.SERIES)
                .count();
        long runtime = candidates.stream()
                .map(ViewCandidate::entry)
                .map(WatchlistEntry::getContent)
                .mapToLong(content -> runtimeMinutes(content) == null ? 0L : runtimeMinutes(content))
                .sum();
        long upcomingCount = candidates.stream()
                .filter(candidate -> candidate.status() == WatchlistStatus.UPCOMING)
                .count();
        return new WatchlistAggregateDTO(candidates.size(), movieCount, seriesCount, runtime, upcomingCount);
    }

    private Page<ViewCandidate> page(List<ViewCandidate> sorted, PageRequest pageRequest) {
        long offset = pageRequest.getOffset();
        int fromIndex = offset >= sorted.size() ? sorted.size() : (int) offset;
        int toIndex = Math.min(fromIndex + pageRequest.getPageSize(), sorted.size());
        return new PageImpl<>(sorted.subList(fromIndex, toIndex), pageRequest, sorted.size());
    }

    private Map<ContentCoordinate, ContentCardDTO> assemblePageCards(
            User target, UUID ownerId, List<ViewCandidate> candidates) {
        return assembleCards(target, ownerId, candidates, VIEW_CARD_FIELDS);
    }

    private Map<ContentCoordinate, ContentCardDTO> assembleCards(
            User target,
            UUID ownerId,
            Collection<ViewCandidate> candidates,
            Set<ContentCardFieldSet> fields) {
        if (candidates.isEmpty()) {
            return Map.of();
        }
        List<ContentCardSpec> specs = candidates.stream()
                .map(candidate -> new ContentCardSpec(
                        ContentCoordinate.from(candidate.entry().getContent()),
                        null,
                        null,
                        candidate.releaseDate(),
                        candidate.entry().getContent().getRuntimeMinutes()))
                .toList();
        Map<ContentCoordinate, ContentCardDTO> cards = contentCardAssembler.assemble(
                specs,
                new ContentCardContext(target.getPreferredLanguage(), target.getPreferredRegion(), ownerId, null),
                fields);
        return cards == null ? Map.of() : cards;
    }

    private record ViewCandidate(
            WatchlistEntry entry, LocalDate releaseDate, WatchlistStatus status) {
    }

    private List<WatchlistEntry> entriesForDateResolution(
            UUID userId, ContentType type, Page<WatchlistEntry> page) {
        Map<UUID, WatchlistEntry> entries = new LinkedHashMap<>();
        page.getContent().forEach(entry -> entries.put(entry.getId(), entry));
        if (type == null || type == ContentType.SERIES) {
            watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(userId, ContentType.SERIES)
                    .forEach(entry -> entries.putIfAbsent(entry.getId(), entry));
        }
        return new ArrayList<>(entries.values());
    }

    private WatchlistEntryResponseDTO withReleaseDate(
            WatchlistEntryResponseDTO response, LocalDate releaseDate) {
        return new WatchlistEntryResponseDTO(
                response.id(), response.type(), response.content(), response.position(),
                response.createdAt(), response.updatedAt(), releaseDate);
    }

    private void assertCanViewWatchlist(UUID viewerId, UUID targetUserId, User target) {
        if (Boolean.TRUE.equals(target.getIsProfilePublic()) || viewerId.equals(targetUserId)) {
            return;
        }

        boolean viewerFollowsTarget = followerRepository
                .existsByFollowerIdAndFollowedIdAndStatus(viewerId, targetUserId, FollowStatus.ACCEPTED);

        if (!viewerFollowsTarget) {
            throw new ForbiddenException("This user profile is private");
        }
    }

    @Override
    @Transactional
    public WatchlistEntryResponseDTO insertEntry(
            UUID userId, ContentType type, WatchlistEntryCreationDTO watchlistEntryCreationDTO) {
        validateType(type);
        advisoryLock.lock(lockIdentity(userId));

        ContentRefCreationDTO contentRefCreation = new ContentRefCreationDTO(
                watchlistEntryCreationDTO.tmdbId(), type, null, null, null, null, null);
        ContentRefDTO contentRef = contentService.getOrCreateReference(contentRefCreation);

        long currentCount = watchlistEntryRepository.countByUserId(userId);
        User user = userRepository.getReferenceById(userId);
        Content content = contentRepository.getReferenceById(contentRef.id());
        releaseDateSnapshotService.writeThrough(user, content);
        LocalDateTime now = LocalDateTime.now();

        WatchlistEntry newEntry = WatchlistEntry.builder()
                .user(user)
                .content(content)
                .type(type)
                .position(Math.toIntExact(currentCount + 1))
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            WatchlistEntry saved = watchlistEntryRepository.save(newEntry);
            watchlistEntryRepository.flush();
            return watchlistEntryMapper.watchlistEntryToResponseDto(saved);
        } catch (DataIntegrityViolationException e) {
            throw mapUniqueConstraintViolation(e);
        }
    }

    @Override
    @Transactional
    public void removeEntry(UUID userId, ContentType type, UUID watchlistEntryId) {
        validateType(type);
        advisoryLock.lock(lockIdentity(userId));

        WatchlistEntry entry = watchlistEntryRepository.findById(watchlistEntryId)
                .orElseThrow(() -> new NotFoundException("Watchlist entry not found"));

        if (!entry.getUser().getId().equals(userId) || entry.getType() != type) {
            throw new NotFoundException("Watchlist entry not found");
        }

        deleteAndCloseGap(entry);
    }

    @Override
    @Transactional
    public void removeEntryIfPresent(UUID userId, ContentType type, UUID contentId) {
        validateType(type);
        advisoryLock.lock(lockIdentity(userId));
        watchlistEntryRepository.findByUserIdAndTypeAndContentId(userId, type, contentId)
                .ifPresent(this::deleteAndCloseGap);
    }

    private void deleteAndCloseGap(WatchlistEntry entry) {
        UUID userId = entry.getUser().getId();
        int removedPosition = entry.getPosition();
        watchlistEntryRepository.delete(entry);
        watchlistEntryRepository.flush();
        watchlistEntryRepository.parkPositionsInRange(
                userId, removedPosition + 1, Integer.MAX_VALUE, POSITION_PARK_OFFSET);
        watchlistEntryRepository.settleParkedPositions(userId, POSITION_PARK_OFFSET, -1);
    }

    @Override
    @Transactional
    public WatchlistEntryResponseDTO moveEntry(
            UUID userId, ContentType type, UUID watchlistEntryId, WatchlistEntryReorderDTO reorderDTO) {
        validateType(type);
        advisoryLock.lock(lockIdentity(userId));

        WatchlistEntry entry = watchlistEntryRepository.findById(watchlistEntryId)
                .orElseThrow(() -> new NotFoundException("Watchlist entry not found"));

        if (!entry.getUser().getId().equals(userId) || entry.getType() != type) {
            throw new NotFoundException("Watchlist entry not found");
        }

        long currentCount = watchlistEntryRepository.countByUserId(userId);
        int oldPosition = entry.getPosition();
        int newPosition = reorderDTO.position();

        if (newPosition > currentCount) {
            throw new BadRequestException(
                    "position cannot be greater than " + currentCount + ", the last position in the watchlist");
        }
        if (newPosition == oldPosition) {
            return watchlistEntryMapper.watchlistEntryToResponseDto(entry);
        }

        try {
            return performMove(entry, oldPosition, newPosition, currentCount);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Watchlist entry could not be reordered due to a concurrent update");
        }
    }

    private WatchlistEntryResponseDTO performMove(
            WatchlistEntry entry, int oldPosition, int newPosition, long currentCount) {
        UUID userId = entry.getUser().getId();
        entry.setPosition(Math.toIntExact(currentCount + 1));
        watchlistEntryRepository.save(entry);
        watchlistEntryRepository.flush();

        boolean movingForward = newPosition < oldPosition;
        int rangeStart = movingForward ? newPosition : oldPosition + 1;
        int rangeEnd = movingForward ? oldPosition - 1 : newPosition;
        int shiftDelta = movingForward ? 1 : -1;

        watchlistEntryRepository.parkPositionsInRange(
                userId, rangeStart, rangeEnd, POSITION_PARK_OFFSET);
        watchlistEntryRepository.settleParkedPositions(userId, POSITION_PARK_OFFSET, shiftDelta);

        entry.setPosition(newPosition);
        WatchlistEntry saved = watchlistEntryRepository.save(entry);
        watchlistEntryRepository.flush();
        return watchlistEntryMapper.watchlistEntryToResponseDto(saved);
    }

    private ConflictException mapUniqueConstraintViolation(DataIntegrityViolationException e) {
        String constraintName = extractConstraintName(e);
        if ("uq_watchlist_entries_user_id_type_content_id".equals(constraintName)) {
            return new ConflictException("This content is already in your watchlist");
        }
        if ("uq_watchlist_entries_user_id_position".equals(constraintName)) {
            return new ConflictException("This position was just taken by a concurrent insert");
        }
        return new ConflictException("Unable to insert this content into your watchlist");
    }

    private String extractConstraintName(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        if (cause instanceof org.hibernate.exception.ConstraintViolationException cve) {
            return cve.getConstraintName();
        }
        return null;
    }

    private void validateType(ContentType type) {
        if (type != null && type != ContentType.MOVIE && type != ContentType.SERIES) {
            throw new BadRequestException("type must be MOVIE or SERIES");
        }
    }

    private String lockIdentity(UUID userId) {
        return "watchlist|" + userId;
    }
}
