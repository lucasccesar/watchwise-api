package com.watchwise.watchwise_api.diaryentry.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.idempotency.UserActionExecutor;
import com.watchwise.watchwise_api.common.idempotency.UserActionOperation;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbGenre;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbProductionCountry;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.common.transaction.UserContentMutationLock;
import com.watchwise.watchwise_api.comment.service.impl.CommentPreviewAssembler;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import com.watchwise.watchwise_api.diarycompletion.service.impl.DiaryProjectionInvalidator;
import com.watchwise.watchwise_api.diarycompletion.model.CompletionScope;
import com.watchwise.watchwise_api.diarycompletion.model.VersionedCompletionScope;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionCalculator;
import com.watchwise.watchwise_api.diarycompletion.service.CompletionScopeLoader;
import com.watchwise.watchwise_api.diaryentry.dto.ContentReviewResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.ContentReviewSource;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryDaySummaryDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryBulkCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationResultDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryUpdateDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiarySeriesOptionDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeasonProgressDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressAggregateDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressPageResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.WatchEventCommand;
import com.watchwise.watchwise_api.diaryentry.dto.WatchMutationResult;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.entity.WatchCompanion;
import com.watchwise.watchwise_api.diaryentry.mapper.DiaryEntryMapper;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryReadRepository;
import com.watchwise.watchwise_api.diaryentry.repository.ImportedWatchAssertionRepository;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntrySort;
import com.watchwise.watchwise_api.diaryentry.repository.ContentReviewSort;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.diaryentry.service.DiaryWatchCommandService;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressReadRepository;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressAssembler;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressMetadataRefreshService;
import com.watchwise.watchwise_api.seriesprogress.service.SeriesProgressPresentationEnricher;
import com.watchwise.watchwise_api.seriesprogress.dto.ProgressEpisodeDTO;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import com.watchwise.watchwise_api.platformimport.service.ImportLedger;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import com.watchwise.watchwise_api.watchlist.service.WatchlistEntryService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.hibernate.exception.ConstraintViolationException;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiaryEntryServiceImplTest {

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Mock
    private DiaryEntryReadRepository diaryEntryReadRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentService contentService;

    @Mock
    private ContentMapper contentMapper;

    @Mock
    private UserContentPosterService userContentPosterService;

    @Mock
    private FollowerRepository followerRepository;

    @Mock
    private DiaryEntryMapper diaryEntryMapper;

    @Mock
    private NewTransactionExecutor newTransactionExecutor;

    @Mock
    private UserContentMutationLock userContentMutationLock;

    @Mock
    private WatchlistEntryService watchlistEntryService;

    @Mock
    private DroppedEntryRepository droppedEntryRepository;

    @Mock
    private LikeService likeService;

    @Mock
    private UserBlockAccessService userBlockAccessService;

    @Mock
    private CommentPreviewAssembler commentPreviewAssembler;

    @Mock
    private WatchCompanionRepository watchCompanionRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private ContentCardAssembler contentCardAssembler;

    @Mock
    private TmdbClient tmdbClient;

    @Mock
    private SeriesProgressReadRepository seriesProgressReadRepository;

    @Mock
    private SeriesProgressMetadataRefreshService seriesProgressMetadataRefreshService;

    @Mock
    private SeriesProgressPresentationEnricher seriesProgressPresentationEnricher;

    @Mock
    private DiaryProjectionInvalidator diaryProjectionInvalidator;

    @Mock
    private DiaryWatchCommandService diaryWatchCommandService;

    @Mock
    private UserActionExecutor userActionExecutor;

    @Mock
    private CompletionScopeLoader completionScopeLoader;

    @Spy
    private CompletionCalculator completionCalculator = new CompletionCalculator();

    @Mock
    private ImportedWatchAssertionRepository importedWatchAssertionRepository;

    @Mock
    private ImportLedger importLedger;

    @Mock
    private ObjectMapper actionObjectMapper;

    @Spy
    private SeriesProgressAssembler seriesProgressAssembler = new SeriesProgressAssembler();

    @Mock
    private EntityManager entityManager;

    @Spy
    private PageRequestFactory pageRequestFactory = new PageRequestFactory();

    @InjectMocks
    private DiaryEntryServiceImpl diaryEntryService;

    @Captor
    private ArgumentCaptor<DiaryEntry> entryCaptor;

    @Captor
    private ArgumentCaptor<List<WatchCompanion>> watchCompanionsCaptor;

    @Captor
    private ArgumentCaptor<PageRequest> pageRequestCaptor;

    @Captor
    private ArgumentCaptor<ContentRefCreationDTO> contentRefCreationCaptor;

    @Captor
    private ArgumentCaptor<List<ContentCardSpec>> cardSpecsCaptor;

    @Captor
    private ArgumentCaptor<ContentCardContext> cardContextCaptor;

    private UUID lucasId;
    private UUID marinaId;
    private User lucas;
    private User marina;
    private Content fightClub;
    private final Map<String, Content> preparedSeriesReferences = new HashMap<>();
    private final Map<String, Content> preparedSeasonReferences = new HashMap<>();
    private final Map<String, Content> preparedEpisodeReferences = new HashMap<>();
    private final Map<String, Content> preparedMovieReferences = new HashMap<>();
    private final Map<UUID, DiaryEntry> persistedWatchEvents = new HashMap<>();
    private DiaryEntryCreationResultDTO serializedBulkResponse;

    @BeforeEach
    void setUp() {
        preparedSeriesReferences.clear();
        preparedSeasonReferences.clear();
        preparedEpisodeReferences.clear();
        preparedMovieReferences.clear();
        persistedWatchEvents.clear();
        serializedBulkResponse = null;
        ReflectionTestUtils.setField(diaryEntryService, "entityManager", entityManager);

        lucasId = UUID.randomUUID();
        marinaId = UUID.randomUUID();

        lucas = User.builder()
                .id(lucasId)
                .username("lucas")
                .email("lucas@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        marina = User.builder()
                .id(marinaId)
                .username("marina")
                .email("marina@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        fightClub = buildContent("550", ContentType.MOVIE);

        lenient().when(likeService.getLikedDiaryEntryIds(any(), any())).thenReturn(Set.of());
        lenient().when(watchCompanionRepository.findByDiaryEntryIdIn(any())).thenReturn(List.of());
        lenient().when(diaryEntryRepository.existsById(any(UUID.class))).thenReturn(true);
        lenient().when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                        any(), anyInt(), isNull(), eq(ContentType.SEASON)))
                .thenAnswer(invocation -> Optional.ofNullable(preparedSeasonReferences.get(
                        seasonReferenceKey(invocation.getArgument(0), invocation.getArgument(1)))));
        lenient().when(contentRepository.findByTmdbIdAndType(any(), eq(ContentType.SERIES)))
                .thenAnswer(invocation -> Optional.ofNullable(preparedSeriesReferences.get(invocation.getArgument(0))));
        lenient().when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        lenient().when(seriesProgressReadRepository.findCandidatesByUserId(
                        any(UUID.class), any(SeriesProgressReadRepository.SeriesProgressSort.class),
                        any(Sort.Direction.class), any(Pageable.class)))
                .thenReturn(Page.empty());
        lenient().when(tmdbClient.getSeasonFullDetails(any(), any(), any()))
                .thenReturn(new TmdbLookupResult.Found<>(emptySeasonDetails()));
        lenient().when(tmdbClient.getTvFullDetails(any(), any()))
                .thenReturn(new TmdbLookupResult.Found<>(emptySeriesDetails()));
        lenient().when(tmdbClient.getMovieFullDetails(any(), any()))
                .thenReturn(new TmdbLookupResult.Found<>(emptyMovieDetails()));
        lenient().when(userContentPosterService.findSeriesPosters(any(), any()))
                .thenReturn(Map.of());
        lenient().when(userContentPosterService.findByUserAndContentIds(any(), any()))
                .thenReturn(Map.of());
        lenient().when(importedWatchAssertionRepository.findByUserIdAndSeriesTmdbId(any(), any()))
                .thenReturn(List.of());
        lenient().when(completionScopeLoader.load(any(MediaReference.class), any(), any(), any(Instant.class)))
                .thenAnswer(invocation -> new VersionedCompletionScope(
                        new CompletionScope(invocation.getArgument(0), Set.of(), true),
                        "unit-test", Instant.now().plusSeconds(60)));
        lenient().when(userActionExecutor.execute(
                        any(UUID.class), any(UserActionOperation.class), any(), any(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<UserActionExecutor.ActionResult>) invocation.getArgument(4)).get());
        lenient().when(actionObjectMapper.writeValueAsString(any()))
                .thenAnswer(invocation -> {
                    serializedBulkResponse = invocation.getArgument(0);
                    return "bulk-response";
                });
        lenient().when(actionObjectMapper.readValue("bulk-response", DiaryEntryCreationResultDTO.class))
                .thenAnswer(invocation -> serializedBulkResponse);
        lenient().when(diaryWatchCommandService.recordPrepared(any(UUID.class), any(WatchEventCommand.class)))
                .thenAnswer(invocation -> persistPreparedWatchEvent(
                        invocation.getArgument(0), invocation.getArgument(1)));
        lenient().when(diaryEntryRepository.findByIdInWithContentAndUser(anyCollection()))
                .thenAnswer(invocation -> ((List<UUID>) invocation.getArgument(0)).stream()
                        .map(persistedWatchEvents::get).filter(Objects::nonNull).toList());
        lenient().when(diaryEntryRepository.findById(any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(persistedWatchEvents.get(invocation.getArgument(0))));
        lenient().when(diaryEntryRepository.findEpisodeEntriesBySeriesForUser(any(UUID.class), any()))
                .thenAnswer(invocation -> persistedWatchEvents.values().stream()
                        .filter(entry -> entry.getUser().getId().equals(invocation.getArgument(0)))
                        .filter(entry -> entry.getContent().getType() == ContentType.EPISODE)
                        .filter(entry -> invocation.getArgument(1).equals(entry.getContent().getSeriesTmdbId()))
                        .toList());
        lenient().when(diaryEntryRepository.findEpisodeEntriesByUserIdAndSeriesTmdbIdAndSeasonNumber(
                        any(UUID.class), any(), anyInt()))
                .thenAnswer(invocation -> persistedWatchEvents.values().stream()
                        .filter(entry -> entry.getUser().getId().equals(invocation.getArgument(0)))
                        .filter(entry -> entry.getContent().getType() == ContentType.EPISODE)
                        .filter(entry -> invocation.getArgument(1).equals(entry.getContent().getSeriesTmdbId()))
                        .filter(entry -> invocation.<Integer>getArgument(2).equals(entry.getContent().getSeasonNumber()))
                        .toList());
        lenient().when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class)))
                .thenAnswer(invocation -> persistedWatchEvents.values().stream()
                        .filter(entry -> entry.getUser().getId().equals(invocation.getArgument(0)))
                        .filter(entry -> entry.getContent().getId().equals(invocation.getArgument(1)))
                        .map(DiaryEntry::getWatchNumber).filter(Objects::nonNull).max(Integer::compareTo).orElse(0));
        lenient().when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(invocation -> buildResponseDtoWithContent(invocation.getArgument(0)));
    }

    private WatchMutationResult persistPreparedWatchEvent(UUID ownerId, WatchEventCommand command) {
        Content content = preparedContent(command.content());
        int watchNumber = command.requestedWatchNumber() == null
                ? diaryEntryRepository.findMaxWatchNumber(ownerId, content.getId()) + 1
                : command.requestedWatchNumber();
        DiaryEntry entry = DiaryEntry.builder()
                .id(UUID.randomUUID())
                .user(ownerId.equals(lucasId) ? lucas : userRepository.getReferenceById(ownerId))
                .content(content)
                .comment(command.comment())
                .score(command.score())
                .watchedDate(command.watchedDate())
                .watchedAt(command.watchedAt())
                .watchNumber(watchNumber)
                .watchedInTheater(command.watchedInTheater())
                .ignore(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        DiaryEntry saved;
        try {
            saved = diaryEntryRepository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException conflict) {
            Throwable cause = conflict.getCause();
            String constraint = cause instanceof ConstraintViolationException violation
                    ? violation.getConstraintName() : null;
            throw new ConflictException("uq_diary_entries_user_content_watch_number".equals(constraint)
                    ? "This watch was already logged by a concurrent request"
                    : "Diary entry could not be saved due to a conflict");
        }
        persistedWatchEvents.put(saved.getId(), saved);
        return new WatchMutationResult(saved.getId(), true, 0);
    }

    private Content preparedContent(MediaReference reference) {
        Content content = switch (reference.type()) {
            case MOVIE -> preparedMovieReferences.get(reference.tmdbId());
            case EPISODE -> preparedEpisodeReferences.get(episodeReferenceKey(
                    reference.seriesTmdbId(), reference.seasonNumber(), reference.episodeNumber()));
            default -> null;
        };
        if (content == null) {
            LocalDateTime now = LocalDateTime.now();
            content = Content.builder()
                    .id(UUID.randomUUID())
                    .type(reference.type())
                    .tmdbId(reference.tmdbId())
                    .seriesTmdbId(reference.seriesTmdbId())
                    .seasonNumber(reference.seasonNumber())
                    .episodeNumber(reference.episodeNumber())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            if (reference.type() == ContentType.MOVIE) {
                preparedMovieReferences.put(reference.tmdbId(), content);
            } else {
                preparedEpisodeReferences.put(episodeReferenceKey(
                        reference.seriesTmdbId(), reference.seasonNumber(), reference.episodeNumber()), content);
            }
        }
        return content;
    }

    private String episodeReferenceKey(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        return seriesTmdbId + ":" + seasonNumber + ":" + episodeNumber;
    }

    private TmdbSeasonFullDetails emptySeasonDetails() {
        return new TmdbSeasonFullDetails(null, null, null, null, null, null, List.of(), null, null);
    }

    private TmdbTvFullDetails emptySeriesDetails() {
        return new TmdbTvFullDetails(null, null, null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, null, null, null, null, null);
    }

    private TmdbTvFullDetails seriesDetailsWithName(String name) {
        return new TmdbTvFullDetails(null, name, null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, null, null, null, null, null);
    }

    private DiaryEntryRepository.DiarySeriesCount seriesCount(String seriesTmdbId, Long entriesCount) {
        return new DiarySeriesCountRow(seriesTmdbId, entriesCount);
    }

    private record DiarySeriesCountRow(String seriesTmdbId, Long entriesCount)
            implements DiaryEntryRepository.DiarySeriesCount {
        @Override
        public String getSeriesTmdbId() {
            return seriesTmdbId;
        }

        @Override
        public Long getEntriesCount() {
            return entriesCount;
        }
    }

    private TmdbTvFullDetails seriesDetailsWithEpisodeCount(int episodeCount) {
        return new TmdbTvFullDetails(null, null, null, null, null, null, null, null, null, null, null,
                List.of(new TmdbSeasonSummary(1, "Season 1", null, null, episodeCount, null)),
                null, null, null, null, 1, episodeCount, null, null, null, null);
    }

    private TmdbTvFullDetails seriesDetailsWithSeasons(TmdbSeasonSummary... seasons) {
        return new TmdbTvFullDetails(null, null, null, null, null, null, null, null, null, null, null,
                List.of(seasons), null, null, null, null, seasons.length, null, null, null, null, null);
    }

    private TmdbSeasonFullDetails seasonDetailsWithAiredEpisodes(int episodeCount) {
        return seasonDetailsWithAiredEpisodes(1, episodeCount);
    }

    private TmdbSeasonFullDetails seasonDetailsWithAiredEpisodes(int seasonNumber, int episodeCount) {
        List<TmdbEpisodeSummary> episodes = IntStream.rangeClosed(1, episodeCount)
                .mapToObj(episodeNumber -> new TmdbEpisodeSummary(
                        episodeNumber, null, null, "2020-01-01", null, null, null))
                .toList();
        return new TmdbSeasonFullDetails(null, null, null, null, null, seasonNumber, episodes, null, null);
    }

    private TmdbMovieFullDetails emptyMovieDetails() {
        return new TmdbMovieFullDetails(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    private TmdbSeasonFullDetails seasonDetailsWithRuntimes(Map<Integer, Integer> runtimeByEpisode) {
        List<TmdbEpisodeSummary> episodes = runtimeByEpisode.entrySet().stream()
                .map(entry -> new TmdbEpisodeSummary(entry.getKey(), null, null, null, entry.getValue(), null, null))
                .toList();
        return new TmdbSeasonFullDetails(null, null, null, null, null, null, episodes, null, null);
    }

    // ---------- getDiaryEntries ----------

    @Test
    @DisplayName("[getDiaryEntry] Should Return Enriched Entry - When Entry Is Visible")
    void shouldReturnEnrichedEntryWhenEntryIsVisible() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        DiaryEntryResponseDTO mapped = buildResponseDto(entry);
        ContentCardDTO card = cardFor(fightClub, "Fight Club", ContentPreviewStatus.AVAILABLE);
        when(diaryEntryRepository.findByIdWithContentAndUser(entry.getId())).thenReturn(Optional.of(entry));
        when(likeService.getLikedDiaryEntryIds(lucasId, List.of(entry.getId()))).thenReturn(Set.of(entry.getId()));
        when(commentPreviewAssembler.countDiaryEntries(List.of(entry.getId()), lucasId))
                .thenReturn(Map.of(entry.getId(), 7L));
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, true, List.of())).thenReturn(mapped);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(ContentCoordinate.from(fightClub), card));

        DiaryEntryResponseDTO result = diaryEntryService.getDiaryEntry(lucasId, entry.getId());

        assertThat(result.commentsCount()).isEqualTo(7L);
        assertThat(result.card()).isEqualTo(card);
        verify(diaryEntryRepository).findByIdWithContentAndUser(entry.getId());
        verify(contentCardAssembler).assemble(anyCollection(), cardContextCaptor.capture(), anySet());
        assertThat(cardContextCaptor.getValue().posterUserId()).isEqualTo(lucasId);
        assertThat(cardContextCaptor.getValue().viewerId()).isEqualTo(lucasId);
    }

    @Test
    @DisplayName("[getDiaryEntry] Should Throw NotFoundException - When Diary Entry Id Does Not Exist")
    void shouldThrowNotFoundExceptionWhenDiaryEntryIdDoesNotExist() {
        UUID missingDiaryEntryId = UUID.randomUUID();
        when(diaryEntryRepository.findByIdWithContentAndUser(missingDiaryEntryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntry(lucasId, missingDiaryEntryId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Diary entry not found");
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Use Read Query - When Non-Default Sort Is Requested")
    void shouldUseReadQueryWhenNonDefaultSortIsRequested() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryReadRepository.findPage(any(), eq(DiaryEntrySort.RATING_DESC), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<DiaryEntryResponseDTO> result = diaryEntryService.getDiaryEntries(
                lucasId, lucasId, null, 1, 10, null, null, null, null, null, null,
                7, 10, DiaryEntrySort.RATING_DESC);

        assertThat(result).isEmpty();
        verify(diaryEntryReadRepository).findPage(any(), eq(DiaryEntrySort.RATING_DESC), any(PageRequest.class));
        verify(diaryEntryRepository, never()).findByUserIdWithFilters(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Reject Invalid Score Range - When Bound Is Outside 1 To 10")
    void shouldRejectInvalidScoreRangeWhenBoundIsOutsideAllowedRange() {
        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(
                lucasId, lucasId, null, 1, 10, null, null, null, null, null, null,
                0, 10, DiaryEntrySort.NEWEST))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("scoreFrom and scoreTo must be between 1 and 10");

        verifyNoInteractions(diaryEntryReadRepository);
    }

    @Test
    @DisplayName("[rawDiaryFilters] Should Direct Series And Season Filters To Diary View - When Requested")
    void shouldDirectSeriesAndSeasonRawFiltersToDiaryViewWhenRequested() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdWithFilters(
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());
        when(diaryEntryReadRepository.findDailySummary(any())).thenReturn(List.of());

        for (ContentType type : List.of(ContentType.SERIES, ContentType.SEASON)) {
            assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(
                    lucasId, lucasId, null, 1, 10, type, null, null, null, null, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("/diary/view");
            assertThatThrownBy(() -> diaryEntryService.getDiaryDailySummary(
                    lucasId, lucasId, type, null, null, null, null, null, null, null))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("/diary/view");
        }

        verifyNoInteractions(diaryEntryRepository, diaryEntryReadRepository);
    }

    @Test
    void shouldHideDiaryWhenTargetAndViewerAreBlocked() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        org.mockito.Mockito.doThrow(new NotFoundException("Resource not found"))
                .when(userBlockAccessService).assertContentVisible(marinaId, lucasId);

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(
                marinaId, lucasId, null, 1, 10, null, null, null, null))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(diaryEntryRepository);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Return Mapped Page - When Viewer Is The Profile Owner")
    void shouldReturnMappedPageWhenViewerIsTheProfileOwner() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        DiaryEntryResponseDTO dto = buildResponseDto(entry);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(entry)));
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false, List.of())).thenReturn(dto);

        Page<DiaryEntryResponseDTO> result = diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 10, null, null, null, null);

        assertThat(result.getContent()).containsExactly(dto);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Assemble One Card Per Selected Page Coordinate - When Entries Share Content")
    void shouldAssembleOneCardPerSelectedPageCoordinateWhenEntriesShareContent() {
        DiaryEntry firstEntry = buildEntry(lucas, fightClub);
        DiaryEntry secondEntry = buildEntry(lucas, fightClub);
        DiaryEntryResponseDTO firstDto = buildResponseDto(firstEntry);
        DiaryEntryResponseDTO secondDto = buildResponseDto(secondEntry);
        ContentCardDTO card = cardFor(fightClub, "Fight Club", ContentPreviewStatus.AVAILABLE);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(firstEntry, secondEntry), PageRequest.of(0, 2), 3));
        when(diaryEntryMapper.diaryEntryToResponseDto(firstEntry, false, List.of())).thenReturn(firstDto);
        when(diaryEntryMapper.diaryEntryToResponseDto(secondEntry, false, List.of())).thenReturn(secondDto);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(ContentCoordinate.from(fightClub), card));

        Page<DiaryEntryResponseDTO> result = diaryEntryService.getDiaryEntries(
                lucasId, lucasId, null, 1, 2, null, null, null, null);

        assertThat(result.getContent()).extracting(DiaryEntryResponseDTO::card)
                .containsExactly(card, card);
        verify(contentCardAssembler).assemble(cardSpecsCaptor.capture(), cardContextCaptor.capture(), anySet());
        assertThat(cardSpecsCaptor.getValue()).extracting(ContentCardSpec::coordinate)
                .containsExactly(ContentCoordinate.from(fightClub));
        assertThat(cardContextCaptor.getValue().posterUserId()).isEqualTo(lucasId);
        assertThat(cardContextCaptor.getValue().viewerId()).isEqualTo(lucasId);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Omit Viewer Card Facets - When Viewer Is Absent")
    void shouldOmitViewerCardFacetsWhenViewerIsAbsent() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(entry)));
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false, List.of())).thenReturn(buildResponseDto(entry));
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(ContentCoordinate.from(fightClub),
                        cardFor(fightClub, "Fight Club", ContentPreviewStatus.AVAILABLE)));

        diaryEntryService.getDiaryEntries(null, lucasId, null, 1, 10, null, null, null, null);

        verify(contentCardAssembler).assemble(anyCollection(), cardContextCaptor.capture(), anySet());
        assertThat(cardContextCaptor.getValue().viewerId()).isNull();
        org.mockito.ArgumentCaptor<Set<ContentCardFieldSet>> fieldsCaptor =
                org.mockito.ArgumentCaptor.forClass(Set.class);
        verify(contentCardAssembler).assemble(anyCollection(), any(ContentCardContext.class), fieldsCaptor.capture());
        assertThat(fieldsCaptor.getValue()).doesNotContain(
                ContentCardFieldSet.VIEWER_STATE, ContentCardFieldSet.WATCHLIST_PROGRESS);
    }

    @Test
    @DisplayName("[getDiaryDailySummary] Should Use The Aggregate Read - Without Paging Or Card Resolution")
    void shouldUseTheAggregateReadWithoutPagingOrCardResolution() {
        LocalDate date = LocalDate.of(2026, 10, 7);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryReadRepository.findDailySummary(any()))
                .thenReturn(List.of(new DiaryEntryReadRepository.DiaryDaySummaryRow(date, 3, 240)));

        List<DiaryDaySummaryDTO> result = diaryEntryService.getDiaryDailySummary(
                lucasId, lucasId, ContentType.MOVIE, date, date, true, null, null, null, null);

        assertThat(result).containsExactly(new DiaryDaySummaryDTO(date, 3, 240));
        verify(diaryEntryReadRepository).findDailySummary(any());
        verify(diaryEntryReadRepository, never()).findPage(any(), any(), any());
        verifyNoInteractions(contentCardAssembler);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Return Canonical Poster - When Poster Was Set Through Another Feature")
    void shouldReturnCanonicalPosterWhenPosterWasSetThroughAnotherFeature() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        DiaryEntryResponseDTO mapped = buildResponseDto(entry);
        String canonicalPoster = "https://image.tmdb.org/t/p/w342/from-list.png";
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(entry)));
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false, List.of())).thenReturn(mapped);
        when(userContentPosterService.findByUserAndContentIds(lucasId, List.of(fightClub.getId())))
                .thenReturn(Map.of(fightClub.getId(), canonicalPoster));

        Page<DiaryEntryResponseDTO> result = diaryEntryService.getDiaryEntries(
                lucasId, lucasId, null, 1, 10, null, null, null, null);

        assertThat(result.getContent()).singleElement()
                .extracting(DiaryEntryResponseDTO::customPosterUrl)
                .isEqualTo(canonicalPoster);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Return Empty Page - When User Has No Entries")
    void shouldReturnEmptyPageWhenUserHasNoEntries() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(eq(lucasId), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<DiaryEntryResponseDTO> result = diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 10, null, null, null, null);

        assertThat(result.getContent()).isEmpty();
        verify(diaryEntryMapper, never()).diaryEntryToResponseDto(any(), anyBoolean());
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Throw NotFoundException - When User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenUserDoesNotExist() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 10, null, null, null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verifyNoInteractions(diaryEntryRepository);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Return Entries - When Target Profile Is Public And Viewer Is A Different User")
    void shouldReturnEntriesWhenTargetProfileIsPublicAndViewerIsADifferentUser() {
        lucas.setIsProfilePublic(true);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(eq(lucasId), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<DiaryEntryResponseDTO> result = diaryEntryService.getDiaryEntries(marinaId, lucasId, null, 1, 10, null, null, null, null);

        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(followerRepository);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Return Entries - When Target Profile Is Private And Viewer Is An Accepted Follower")
    void shouldReturnEntriesWhenTargetProfileIsPrivateAndViewerIsAnAcceptedFollower() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(eq(lucasId), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<DiaryEntryResponseDTO> result = diaryEntryService.getDiaryEntries(marinaId, lucasId, null, 1, 10, null, null, null, null);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateAndViewerIsNotAnAcceptedFollower() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(marinaId, lucasId, null, 1, 10, null, null, null, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");

        verifyNoInteractions(diaryEntryRepository);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Query By Watched Date Range - When Year Is Provided")
    void shouldQueryByWatchedDateRangeWhenYearIsProvided() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdWithFilters(
                eq(lucasId), isNull(), eq(LocalDate.of(2024, 1, 1)), eq(LocalDate.of(2024, 12, 31)), isNull(),
                isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(Page.empty());

        diaryEntryService.getDiaryEntries(lucasId, lucasId, 2024, 1, 10, null, null, null, null);

        verify(diaryEntryRepository).findByUserIdWithFilters(
                eq(lucasId), isNull(), eq(LocalDate.of(2024, 1, 1)), eq(LocalDate.of(2024, 12, 31)), isNull(),
                isNull(), isNull(), any(PageRequest.class));
        verify(diaryEntryRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Query Without Date Filter - When Year Is Not Provided")
    void shouldQueryWithoutDateFilterWhenYearIsNotProvided() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(eq(lucasId), any(PageRequest.class)))
                .thenReturn(Page.empty());

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 10, null, null, null, null);

        verify(diaryEntryRepository, never())
                .findByUserIdWithFilters(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Throw BadRequestException - When Year Is Combined With DateFrom")
    void shouldThrowBadRequestExceptionWhenYearIsCombinedWithDateFrom() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(
                lucasId, lucasId, 2024, 1, 10, null, LocalDate.of(2024, 6, 1), null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("year cannot be combined with dateFrom/dateTo");

        verifyNoInteractions(diaryEntryRepository);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Query With Filters - When Type Is Provided")
    void shouldQueryWithFiltersWhenTypeIsProvided() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdWithFilters(
                eq(lucasId), eq(ContentType.EPISODE), isNull(), isNull(), isNull(), isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(Page.empty());

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 10, ContentType.EPISODE, null, null, null);

        verify(diaryEntryRepository).findByUserIdWithFilters(
                eq(lucasId), eq(ContentType.EPISODE), isNull(), isNull(), isNull(), isNull(), isNull(), any(PageRequest.class));
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Query With Filters - When HasReview Is Provided")
    void shouldQueryWithFiltersWhenHasReviewIsProvided() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdWithFilters(
                eq(lucasId), isNull(), isNull(), isNull(), eq(true), isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(Page.empty());

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 10, null, null, null, true);

        verify(diaryEntryRepository).findByUserIdWithFilters(
                eq(lucasId), isNull(), isNull(), isNull(), eq(true), isNull(), isNull(), any(PageRequest.class));
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Forward Series And Score Filters - When Both Are Provided")
    void shouldForwardSeriesAndScoreFiltersWhenBothAreProvided() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdWithFilters(
                eq(lucasId), isNull(), isNull(), isNull(), isNull(), eq("1399"), eq(10), any(PageRequest.class)))
                .thenReturn(Page.empty());

        diaryEntryService.getDiaryEntries(
                lucasId, lucasId, null, 1, 10, null, null, null, null, "1399", 10);

        verify(diaryEntryRepository).findByUserIdWithFilters(
                eq(lucasId), isNull(), isNull(), isNull(), isNull(), eq("1399"), eq(10), any(PageRequest.class));
    }

    @Test
    @DisplayName("[getDiarySeriesOptions] Should Map And Sort Options - When Series Counts Exist")
    void shouldMapAndSortOptionsWhenSeriesCountsExist() {
        lucas.setPreferredLanguage("pt-BR");
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findSeriesEntryCountsByUserId(lucasId)).thenReturn(List.of(
                seriesCount("1399", 3L),
                seriesCount("550", 2L),
                seriesCount("999", 2L),
                seriesCount("111", 2L),
                seriesCount("000", 2L)));
        when(tmdbClient.getTvFullDetails("1399", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(seriesDetailsWithName("Zeta")));
        when(tmdbClient.getTvFullDetails("550", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(seriesDetailsWithName("Beta")));
        when(tmdbClient.getTvFullDetails("999", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(seriesDetailsWithName("alpha")));
        when(tmdbClient.getTvFullDetails("111", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(seriesDetailsWithName("ALPHA")));
        when(tmdbClient.getTvFullDetails("000", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(seriesDetailsWithName(null)));

        List<DiarySeriesOptionDTO> result = diaryEntryService.getDiarySeriesOptions(lucasId, lucasId);

        assertThat(result).extracting(
                DiarySeriesOptionDTO::seriesTmdbId,
                DiarySeriesOptionDTO::title,
                DiarySeriesOptionDTO::entriesCount)
                .containsExactly(
                        tuple("1399", "Zeta", 3L),
                        tuple("111", "ALPHA", 2L),
                        tuple("999", "alpha", 2L),
                        tuple("550", "Beta", 2L),
                        tuple("000", null, 2L));
        verify(tmdbClient).getTvFullDetails("1399", "pt-BR");
        verify(tmdbClient).getTvFullDetails("550", "pt-BR");
        verify(tmdbClient).getTvFullDetails("999", "pt-BR");
        verify(tmdbClient).getTvFullDetails("111", "pt-BR");
        verify(tmdbClient).getTvFullDetails("000", "pt-BR");
    }

    @Test
    @DisplayName("[getDiarySeriesOptions] Should Return Null Title - When TMDB Does Not Find A Series")
    void shouldReturnNullTitleWhenTmdbDoesNotFindASeries() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findSeriesEntryCountsByUserId(lucasId))
                .thenReturn(List.of(seriesCount("1399", 2L)));
        when(tmdbClient.getTvFullDetails("1399", lucas.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.NotFound<>());

        List<DiarySeriesOptionDTO> result = diaryEntryService.getDiarySeriesOptions(lucasId, lucasId);

        assertThat(result).containsExactly(new DiarySeriesOptionDTO("1399", null, 2L));
    }

    @Test
    @DisplayName("[getDiarySeriesOptions] Should Throw TmdbUnavailableException - When TMDB Is Unavailable")
    void shouldThrowTmdbUnavailableExceptionWhenTmdbIsUnavailable() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findSeriesEntryCountsByUserId(lucasId))
                .thenReturn(List.of(seriesCount("1399", 2L)));
        when(tmdbClient.getTvFullDetails("1399", lucas.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.Unavailable<>());

        assertThatThrownBy(() -> diaryEntryService.getDiarySeriesOptions(lucasId, lucasId))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Throw BadRequestException - When Score Is Outside The Allowed Range")
    void shouldThrowBadRequestExceptionWhenScoreIsOutsideTheAllowedRange() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(
                lucasId, lucasId, null, 1, 10, null, null, null, null, null, 11))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("score must be between 1 and 10");

        verifyNoInteractions(diaryEntryRepository, tmdbClient);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Throw BadRequestException - When Series ID Is Blank")
    void shouldThrowBadRequestExceptionWhenSeriesIdIsBlank() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(
                lucasId, lucasId, null, 1, 10, null, null, null, null, "  ", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("seriesTmdbId cannot be blank");

        verifyNoInteractions(diaryEntryRepository, tmdbClient);
    }

    @Test
    @DisplayName("[getDiarySeriesOptions] Should Authorize Before Loading Counts Or TMDB Details - When Target Profile Is Private")
    void shouldAuthorizeBeforeLoadingCountsOrTmdbDetailsWhenTargetProfileIsPrivate() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> diaryEntryService.getDiarySeriesOptions(marinaId, lucasId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");

        verifyNoInteractions(diaryEntryRepository, tmdbClient);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Throw BadRequestException - When Year Is Out Of Range")
    void shouldThrowBadRequestExceptionWhenYearIsOutOfRange() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(lucasId, lucasId, Integer.MAX_VALUE, 1, 10, null, null, null, null))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(diaryEntryRepository);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPageWhenPageNumberIsNull() {
        stubEmptyDiaryPage();

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, null, 10, null, null, null, null);

        verify(diaryEntryRepository).findByUserIdOrderByCreatedAtDesc(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(PageRequestFactory.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Use Default Page - When Page Number Is Zero")
    void shouldUseDefaultPageWhenPageNumberIsZero() {
        stubEmptyDiaryPage();

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 0, 10, null, null, null, null);

        verify(diaryEntryRepository).findByUserIdOrderByCreatedAtDesc(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(PageRequestFactory.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Use Page Number Minus One - When Page Number Is Positive")
    void shouldUsePageNumberMinusOneWhenPageNumberIsPositive() {
        stubEmptyDiaryPage();

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 3, 10, null, null, null, null);

        verify(diaryEntryRepository).findByUserIdOrderByCreatedAtDesc(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Throw BadRequestException - When Page Number Is Negative")
    void shouldThrowBadRequestExceptionWhenPageNumberIsNegative() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(lucasId, lucasId, null, -1, 10, null, null, null, null))
                .isInstanceOf(BadRequestException.class);

        verify(diaryEntryRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Use Default Page Size - When Page Size Is Null")
    void shouldUseDefaultPageSizeWhenPageSizeIsNull() {
        stubEmptyDiaryPage();

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, null, null, null, null, null);

        verify(diaryEntryRepository).findByUserIdOrderByCreatedAtDesc(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(PageRequestFactory.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Clamp Page Size To Max Limit - When Page Size Exceeds Limit")
    void shouldClampPageSizeToMaxLimitWhenPageSizeExceedsLimit() {
        stubEmptyDiaryPage();

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 1001, null, null, null, null);

        verify(diaryEntryRepository).findByUserIdOrderByCreatedAtDesc(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(PageRequestFactory.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Use Provided Page Size - When Page Size Is Valid")
    void shouldUseProvidedPageSizeWhenPageSizeIsValid() {
        stubEmptyDiaryPage();

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 25, null, null, null, null);

        verify(diaryEntryRepository).findByUserIdOrderByCreatedAtDesc(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Use Provided Page Size - When Page Size Is At Max Limit")
    void shouldUseProvidedPageSizeWhenPageSizeIsAtMaxLimit() {
        stubEmptyDiaryPage();

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 1000, null, null, null, null);

        verify(diaryEntryRepository).findByUserIdOrderByCreatedAtDesc(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(1000);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Throw BadRequestException - When Page Size Is Negative")
    void shouldThrowBadRequestExceptionWhenPageSizeIsNegative() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, -5, null, null, null, null))
                .isInstanceOf(BadRequestException.class);

        verify(diaryEntryRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Throw BadRequestException - When Page Size Is Zero")
    void shouldThrowBadRequestExceptionWhenPageSizeIsZero() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 0, null, null, null, null))
                .isInstanceOf(BadRequestException.class);

        verify(diaryEntryRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
    }

    // ---------- getSeriesInProgress ----------

    @Test
    @DisplayName("[getSeriesInProgress] Should Build Detailed Envelope With Global Metrics And Directional Ordering")
    void shouldBuildDetailedEnvelopeWithGlobalMetricsAndDirectionalOrderingForSeriesInProgress() {
        DiaryEntryRepository.SeasonProgress progressA = seasonProgress("a", 1, 2L, 40L);
        DiaryEntryRepository.SeasonProgress progressB = seasonProgress("b", 1, 1L, 20L);
        List<SeriesProgressReadRepository.SeriesProgressCandidate> candidates = List.of(
                progressCandidate("a", 2L, 40L, 2, 9, LocalDate.of(2026, 9, 20), 1, 2, 5, 100, LocalDate.of(2026, 9, 21), 3L, 60L),
                progressCandidate("b", 3L, 60L, 1, 4, LocalDate.of(2026, 9, 19), 1, 1, 4, 80, LocalDate.of(2026, 9, 22), 1L, 20L),
                progressCandidate("off-page", 4L, 200L, 3, 7, LocalDate.of(2026, 9, 18), 3, 2, 3, null, null, 0L, null));

        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(seriesProgressReadRepository.findCandidatesByUserId(
                eq(lucasId), eq(SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED),
                eq(Sort.Direction.DESC), any(Pageable.class)))
                .thenAnswer(invocation -> candidatesPage(invocation.getArgument(3), candidates, false));
        when(seriesProgressReadRepository.findCandidatesByUserId(
                eq(lucasId), eq(SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED),
                eq(Sort.Direction.ASC), any(Pageable.class)))
                .thenAnswer(invocation -> candidatesPage(invocation.getArgument(3), candidates.reversed(), true));
        when(seriesProgressReadRepository.findGlobalTotalsByUserId(lucasId))
                .thenReturn(seriesProgressTotals(9L, 300L));
        when(diaryEntryRepository.findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(
                eq(lucasId), any(List.class)))
                .thenReturn(List.of(progressA, progressB));
        when(seriesProgressMetadataRefreshService.getSnapshotsForRead(any(), any()))
                .thenAnswer(invocation -> ((List<String>) invocation.getArgument(0)).stream()
                        .collect(Collectors.toMap(id -> id, this::snapshot)));

        SeriesInProgressPageResponseDTO result = diaryEntryService.getSeriesInProgress(
                lucasId, lucasId, 1, 2,
                SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED, Sort.Direction.DESC);

        assertThat(result.content()).extracting(SeriesInProgressResponseDTO::seriesTmdbId)
                .containsExactly("a", "b");
        assertThat(result.content().getFirst())
                .extracting(SeriesInProgressResponseDTO::lastWatchedSeasonNumber,
                        SeriesInProgressResponseDTO::lastWatchedEpisodeNumber,
                        SeriesInProgressResponseDTO::remainingEpisodeCount,
                        SeriesInProgressResponseDTO::remainingRuntimeMinutes)
                .containsExactly(1, 2, 3L, 60L);
        assertThat(result.content().getFirst().seasonProgress())
                .extracting(SeasonProgressDTO::seasonNumber,
                        SeasonProgressDTO::remainingEpisodeCount,
                        SeasonProgressDTO::remainingRuntimeMinutes)
                .containsExactly(tuple(1, 3L, 60L));
        assertThat(result.content().getFirst().seasonProgress())
                .extracting(SeasonProgressDTO::seasonNumber)
                .doesNotContain(0);
        assertThat(result.aggregate())
                .isEqualTo(new SeriesInProgressAggregateDTO(3L, 9L, 12L, 3L, null));
        assertThat(result.totalElements()).isEqualTo(3L);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
        verifyNoInteractions(tmdbClient);

        SeriesInProgressPageResponseDTO ascending = diaryEntryService.getSeriesInProgress(
                lucasId, lucasId, 1, 2,
                SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED, Sort.Direction.ASC);

        assertThat(ascending.content()).extracting(SeriesInProgressResponseDTO::seriesTmdbId)
                .containsExactly("off-page", "b");
        verify(seriesProgressReadRepository, times(2)).findCandidatesByUserId(
                eq(lucasId), eq(SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED),
                eq(Sort.Direction.ASC), any(Pageable.class));
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Merge Presentation Enrichment Without Changing Numeric Progress")
    void shouldMergePresentationEnrichmentWithoutChangingNumericProgressForSeriesInProgress() {
        String seriesTmdbId = "1399";
        SeriesProgressReadRepository.SeriesProgressCandidate candidate = progressCandidate(
                seriesTmdbId, 14L, 900L, 5, 14, LocalDate.of(2026, 9, 21),
                5, 14, 16, 900, LocalDate.of(2026, 9, 22), 2L, 112L);

        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(seriesProgressReadRepository.findCandidatesByUserId(
                eq(lucasId), eq(SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED),
                eq(Sort.Direction.DESC), any(Pageable.class)))
                .thenAnswer(invocation -> candidatesPage(invocation.getArgument(3), List.of(candidate), false));
        when(seriesProgressReadRepository.findGlobalTotalsByUserId(lucasId))
                .thenReturn(seriesProgressTotals(14L, 900L));
        when(diaryEntryRepository.findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(
                eq(lucasId), eq(List.of(seriesTmdbId))))
                .thenReturn(List.of(seasonProgress(seriesTmdbId, 5, 14L, 900L)));
        when(seriesProgressMetadataRefreshService.getSnapshotsForRead(eq(List.of(seriesTmdbId)), any()))
                .thenReturn(Map.of(seriesTmdbId, new SeriesProgressMetadataRefreshService.Snapshot(
                        new SeriesProgressMetadataRefreshService.SeriesSnapshot(
                                seriesTmdbId, 16, 1012, 16, LocalDate.of(2026, 9, 22),
                                LocalDateTime.now(), LocalDateTime.now()),
                        List.of(new SeriesProgressMetadataRefreshService.SeasonSnapshot(
                                seriesTmdbId, 5, 16, 1012, 16,
                                LocalDate.of(2026, 9, 22), LocalDateTime.now())))));
        when(seriesProgressPresentationEnricher.enrich(
                any(), any(), any(), any(), any()))
                .thenReturn(Map.of(seriesTmdbId, new SeriesProgressPresentationEnricher.Enrichment(
                        "Breaking Bad", "/poster.jpg", "Ozymandias",
                        new ProgressEpisodeDTO(5, 15, "Granite State", LocalDate.of(2013, 9, 22),
                                55, "/still.jpg", true))));

        SeriesInProgressResponseDTO result = diaryEntryService.getSeriesInProgress(
                lucasId, lucasId, 1, 10,
                SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED, Sort.Direction.DESC)
                .content().getFirst();

        assertThat(result.seriesTitle()).isEqualTo("Breaking Bad");
        assertThat(result.seriesPosterPath()).isEqualTo("/poster.jpg");
        assertThat(result.lastWatchedEpisodeTitle()).isEqualTo("Ozymandias");
        assertThat(result.nextEpisode().episodeNumber()).isEqualTo(15);
        assertThat(result.watchedEpisodeCount()).isEqualTo(14L);
        assertThat(result.totalEpisodeCount()).isEqualTo(16);
        assertThat(result.remainingEpisodeCount()).isEqualTo(2L);
        assertThat(result.remainingRuntimeMinutes()).isEqualTo(112L);
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Expose The Owner's Custom Poster")
    void shouldExposeOwnersCustomPosterForDetailedSeriesInProgress() {
        String seriesTmdbId = "poster-series";
        SeriesProgressReadRepository.SeriesProgressCandidate candidate = progressCandidate(
                seriesTmdbId, 1L, 20L, 1, 1, LocalDate.of(2026, 9, 20), 1, 1,
                3, 60, LocalDate.of(2026, 9, 21), 2L, 40L);

        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(seriesProgressReadRepository.findCandidatesByUserId(
                eq(lucasId), eq(SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED),
                eq(Sort.Direction.DESC), any(Pageable.class)))
                .thenAnswer(invocation -> candidatesPage(invocation.getArgument(3), List.of(candidate), false));
        when(seriesProgressReadRepository.findGlobalTotalsByUserId(lucasId))
                .thenReturn(seriesProgressTotals(1L, 20L));
        when(diaryEntryRepository.findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(
                eq(lucasId), eq(List.of(seriesTmdbId))))
                .thenReturn(List.of());
        when(seriesProgressMetadataRefreshService.getSnapshotsForRead(eq(List.of(seriesTmdbId)), any()))
                .thenReturn(Map.of(seriesTmdbId, snapshot(seriesTmdbId)));
        when(userContentPosterService.findSeriesPosters(lucasId, List.of(seriesTmdbId)))
                .thenReturn(Map.of(seriesTmdbId, "https://image.tmdb.org/t/p/w342/lucas-series-poster.jpg"));

        SeriesInProgressPageResponseDTO result = diaryEntryService.getSeriesInProgress(
                lucasId, lucasId, 1, 10,
                SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED, Sort.Direction.DESC);

        assertThat(result.content().getFirst().customPosterUrl())
                .isEqualTo("https://image.tmdb.org/t/p/w342/lucas-series-poster.jpg");
        verify(userContentPosterService).findSeriesPosters(lucasId, List.of(seriesTmdbId));
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Expose The Target User's Poster To An Authorized Viewer")
    void shouldExposeTargetUsersPosterToAuthorizedViewerForDetailedSeriesInProgress() {
        String seriesTmdbId = "poster-series";
        SeriesProgressReadRepository.SeriesProgressCandidate candidate = progressCandidate(
                seriesTmdbId, 1L, 20L, 1, 1, LocalDate.of(2026, 9, 20), 1, 1,
                3, 60, LocalDate.of(2026, 9, 21), 2L, 40L);
        lucas.setIsProfilePublic(false);

        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(
                marinaId, lucasId, FollowStatus.ACCEPTED)).thenReturn(true);
        when(seriesProgressReadRepository.findCandidatesByUserId(
                eq(lucasId), eq(SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED),
                eq(Sort.Direction.DESC), any(Pageable.class)))
                .thenAnswer(invocation -> candidatesPage(invocation.getArgument(3), List.of(candidate), false));
        when(seriesProgressReadRepository.findGlobalTotalsByUserId(lucasId))
                .thenReturn(seriesProgressTotals(1L, 20L));
        when(diaryEntryRepository.findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(
                eq(lucasId), eq(List.of(seriesTmdbId))))
                .thenReturn(List.of());
        when(seriesProgressMetadataRefreshService.getSnapshotsForRead(eq(List.of(seriesTmdbId)), any()))
                .thenReturn(Map.of(seriesTmdbId, snapshot(seriesTmdbId)));
        when(userContentPosterService.findSeriesPosters(lucasId, List.of(seriesTmdbId)))
                .thenReturn(Map.of(seriesTmdbId, "https://image.tmdb.org/t/p/w342/lucas-series-poster.jpg"));

        SeriesInProgressPageResponseDTO result = diaryEntryService.getSeriesInProgress(
                marinaId, lucasId, 1, 10,
                SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED, Sort.Direction.DESC);

        assertThat(result.content().getFirst().customPosterUrl())
                .isEqualTo("https://image.tmdb.org/t/p/w342/lucas-series-poster.jpg");
        verify(userContentPosterService).findSeriesPosters(lucasId, List.of(seriesTmdbId));
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Return Null Custom Poster When The Target Has None")
    void shouldReturnNullCustomPosterWhenDetailedSeriesInProgressHasNoPoster() {
        String seriesTmdbId = "poster-series";
        SeriesProgressReadRepository.SeriesProgressCandidate candidate = progressCandidate(
                seriesTmdbId, 1L, 20L, 1, 1, LocalDate.of(2026, 9, 20), 1, 1,
                3, 60, LocalDate.of(2026, 9, 21), 2L, 40L);

        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(seriesProgressReadRepository.findCandidatesByUserId(
                eq(lucasId), eq(SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED),
                eq(Sort.Direction.DESC), any(Pageable.class)))
                .thenAnswer(invocation -> candidatesPage(invocation.getArgument(3), List.of(candidate), false));
        when(seriesProgressReadRepository.findGlobalTotalsByUserId(lucasId))
                .thenReturn(seriesProgressTotals(1L, 20L));
        when(diaryEntryRepository.findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(
                eq(lucasId), eq(List.of(seriesTmdbId))))
                .thenReturn(List.of());
        when(seriesProgressMetadataRefreshService.getSnapshotsForRead(eq(List.of(seriesTmdbId)), any()))
                .thenReturn(Map.of(seriesTmdbId, snapshot(seriesTmdbId)));
        when(userContentPosterService.findSeriesPosters(lucasId, List.of(seriesTmdbId)))
                .thenReturn(Map.of());

        SeriesInProgressPageResponseDTO result = diaryEntryService.getSeriesInProgress(
                lucasId, lucasId, 1, 10,
                SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED, Sort.Direction.DESC);

        assertThat(result.content().getFirst().customPosterUrl()).isNull();
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Keep Remaining Runtime Null When A Watched Runtime Is Unknown")
    void shouldKeepRemainingRuntimeNullWhenWatchedRuntimeIsUnknown() {
        String seriesTmdbId = "unknown-runtime";
        SeriesProgressReadRepository.SeriesProgressCandidate candidate = progressCandidate(
                seriesTmdbId, 1L, null, 1, 1, LocalDate.of(2026, 9, 20), 1, 1,
                2, 100, LocalDate.of(2026, 9, 21), 1L, null, false);
        SeriesProgressMetadataRefreshService.Snapshot snapshot = new SeriesProgressMetadataRefreshService.Snapshot(
                new SeriesProgressMetadataRefreshService.SeriesSnapshot(
                        seriesTmdbId, 2, 100, 2, LocalDate.of(2026, 9, 21),
                        LocalDateTime.now(), LocalDateTime.now()),
                List.of(new SeriesProgressMetadataRefreshService.SeasonSnapshot(
                        seriesTmdbId, 1, 2, 100, 2, LocalDate.of(2026, 9, 21), LocalDateTime.now())));

        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(seriesProgressReadRepository.findCandidatesByUserId(
                eq(lucasId), eq(SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED),
                eq(Sort.Direction.DESC), any(Pageable.class)))
                .thenAnswer(invocation -> candidatesPage(invocation.getArgument(3), List.of(candidate), false));
        when(seriesProgressReadRepository.findGlobalTotalsByUserId(lucasId))
                .thenReturn(seriesProgressTotals(1L, null, false));
        when(diaryEntryRepository.findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(
                eq(lucasId), eq(List.of(seriesTmdbId))))
                .thenReturn(List.of(seasonProgress(seriesTmdbId, 1, 1L, null, false)));
        when(seriesProgressMetadataRefreshService.getSnapshotsForRead(eq(List.of(seriesTmdbId)), any()))
                .thenReturn(Map.of(seriesTmdbId, snapshot));

        SeriesInProgressPageResponseDTO result = diaryEntryService.getSeriesInProgress(
                lucasId, lucasId, 1, 10,
                SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED, Sort.Direction.DESC);

        assertThat(result.content().getFirst().remainingRuntimeMinutes()).isNull();
        assertThat(result.content().getFirst().seasonProgress().getFirst().remainingRuntimeMinutes()).isNull();
        assertThat(result.aggregate().remainingRuntimeMinutes()).isNull();
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Exclude Regular Seasons With No Released Episodes")
    void shouldExcludeRegularSeasonsWithNoReleasedEpisodesForDetailedSeriesInProgress() {
        String seriesTmdbId = "zero-released-season";
        SeriesProgressReadRepository.SeriesProgressCandidate candidate = progressCandidate(
                seriesTmdbId, 1L, 20L, 1, 1, LocalDate.of(2026, 9, 20), 1, 1,
                2, 40, LocalDate.of(2026, 9, 21), 1L, 20L);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(seriesProgressReadRepository.findCandidatesByUserId(
                eq(lucasId), eq(SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED),
                eq(Sort.Direction.DESC), any(Pageable.class)))
                .thenAnswer(invocation -> candidatesPage(
                        invocation.getArgument(3), List.of(candidate), false));
        when(seriesProgressReadRepository.findGlobalTotalsByUserId(lucasId))
                .thenReturn(seriesProgressTotals(1L, 20L));
        when(diaryEntryRepository.findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(
                eq(lucasId), eq(List.of(seriesTmdbId))))
                .thenReturn(List.of(seasonProgress(seriesTmdbId, 1, 1L, 20L)));
        when(seriesProgressMetadataRefreshService.getSnapshotsForRead(eq(List.of(seriesTmdbId)), any()))
                .thenReturn(Map.of(seriesTmdbId, new SeriesProgressMetadataRefreshService.Snapshot(
                        new SeriesProgressMetadataRefreshService.SeriesSnapshot(
                                seriesTmdbId, 2, 40, 2, LocalDate.of(2026, 9, 21),
                                LocalDateTime.now(), LocalDateTime.now()),
                        List.of(
                                new SeriesProgressMetadataRefreshService.SeasonSnapshot(
                                        seriesTmdbId, 0, 1, 20, 1, LocalDate.of(2026, 9, 1), LocalDateTime.now()),
                                new SeriesProgressMetadataRefreshService.SeasonSnapshot(
                                        seriesTmdbId, 1, 2, 40, 2, LocalDate.of(2026, 9, 21), LocalDateTime.now()),
                                new SeriesProgressMetadataRefreshService.SeasonSnapshot(
                                        seriesTmdbId, 2, 0, 0, 0, null, LocalDateTime.now())))));

        SeriesInProgressPageResponseDTO result = diaryEntryService.getSeriesInProgress(
                lucasId, lucasId, 1, 10,
                SeriesProgressReadRepository.SeriesProgressSort.LAST_WATCHED, Sort.Direction.DESC);

        assertThat(result.content().getFirst().seasonProgress())
                .extracting(SeasonProgressDTO::seasonNumber, SeasonProgressDTO::totalEpisodeCount)
                .containsExactly(tuple(1, 2));
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Return Mapped Page - When Viewer Is The Profile Owner")
    void shouldReturnMappedPageWhenViewerIsTheProfileOwnerForSeriesInProgress() {
        DiaryEntryRepository.SeriesInProgress row = seriesInProgress("1399", 8, 6, LocalDate.of(2024, 5, 1));
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        Page<SeriesInProgressResponseDTO> result = diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, 10);

        assertThat(result.getContent()).singleElement().satisfies(progress -> {
            assertThat(progress.seriesTmdbId()).isEqualTo("1399");
            assertThat(progress.maxSeasonNumber()).isEqualTo(8);
            assertThat(progress.maxEpisodeNumber()).isEqualTo(6);
            assertThat(progress.lastWatchedDate()).isEqualTo(LocalDate.of(2024, 5, 1));
            assertThat(progress.lastWatchedSeasonNumber()).isEqualTo(8);
            assertThat(progress.lastWatchedEpisodeNumber()).isEqualTo(6);
        });
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Calculate Watched Percentage - When TMDB Provides Total Episode Count")
    void shouldCalculateWatchedPercentageForSeriesInProgress() {
        DiaryEntryRepository.SeriesInProgress row = seriesInProgress(
                "1399", 3L, 8, 6, LocalDate.of(2024, 5, 1));
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(seriesProgressMetadataRefreshService.getSnapshotsForRead(eq(List.of("1399")), any()))
                .thenReturn(Map.of("1399", progressSnapshot("1399", 10, List.of(
                        new SeriesProgressMetadataRefreshService.SeasonSnapshot(
                                "1399", 1, 10, null, null, LocalDate.of(2020, 1, 1), LocalDateTime.now())))));

        Page<SeriesInProgressResponseDTO> result = diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, 10);

        assertThat(result.getContent().getFirst())
                .extracting(SeriesInProgressResponseDTO::watchedEpisodeCount,
                        SeriesInProgressResponseDTO::totalEpisodeCount,
                        SeriesInProgressResponseDTO::watchedPercentage)
                .containsExactly(3L, 10, 30.0);
        verify(seriesProgressMetadataRefreshService).getSnapshotsForRead(eq(List.of("1399")), any());
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Return Progress For Each Released Positive Season And Ignore Specials")
    void shouldReturnProgressForEachReleasedPositiveSeasonAndIgnoreSpecials() {
        DiaryEntryRepository.SeriesInProgress row = seriesInProgress(
                "1399", 4L, 2, 2, LocalDate.of(2024, 5, 1));
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(eq(lucasId), eq(List.of("1399"))))
                .thenReturn(List.of(seasonProgress("1399", 1, 2L, null), seasonProgress("1399", 2, 2L, null)));
        when(seriesProgressMetadataRefreshService.getSnapshotsForRead(eq(List.of("1399")), any()))
                .thenReturn(Map.of("1399", progressSnapshot("1399", 6, List.of(
                        new SeriesProgressMetadataRefreshService.SeasonSnapshot(
                                "1399", 1, 4, null, null, LocalDate.of(2020, 1, 1), LocalDateTime.now()),
                        new SeriesProgressMetadataRefreshService.SeasonSnapshot(
                                "1399", 2, 2, null, null, LocalDate.of(2020, 1, 1), LocalDateTime.now())))));

        Page<SeriesInProgressResponseDTO> result = diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, 10);

        assertThat(result.getContent().getFirst().seasonProgress())
                .extracting(SeasonProgressDTO::seasonNumber,
                        SeasonProgressDTO::watchedEpisodeCount,
                        SeasonProgressDTO::totalEpisodeCount,
                        SeasonProgressDTO::watchedPercentage)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 2L, 4, 50.0),
                        org.assertj.core.groups.Tuple.tuple(2, 2L, 2, 100.0));
        assertThat(result.getContent().getFirst())
                .extracting(SeriesInProgressResponseDTO::totalEpisodeCount,
                        SeriesInProgressResponseDTO::watchedPercentage)
                .containsExactly(6, 66.66666666666667);
        verify(seriesProgressMetadataRefreshService).getSnapshotsForRead(eq(List.of("1399")), any());
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Exclude Future Episodes From Progress Total")
    void shouldExcludeFutureEpisodesFromSeriesProgressTotal() {
        DiaryEntryRepository.SeriesInProgress row = seriesInProgress(
                "1399", 2L, 1, 2, LocalDate.of(2024, 5, 1));
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(eq(lucasId), eq(List.of("1399"))))
                .thenReturn(List.of(seasonProgress("1399", 1, 2L, null)));
        when(seriesProgressMetadataRefreshService.getSnapshotsForRead(eq(List.of("1399")), any()))
                .thenReturn(Map.of("1399", progressSnapshot("1399", 2, List.of(
                        new SeriesProgressMetadataRefreshService.SeasonSnapshot(
                                "1399", 1, 2, null, null, LocalDate.now(), LocalDateTime.now())))));

        Page<SeriesInProgressResponseDTO> result = diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, 10);

        assertThat(result.getContent().getFirst())
                .extracting(SeriesInProgressResponseDTO::watchedEpisodeCount,
                        SeriesInProgressResponseDTO::totalEpisodeCount,
                        SeriesInProgressResponseDTO::watchedPercentage)
                .containsExactly(2L, 2, 100.0);
        assertThat(result.getContent().getFirst().seasonProgress().getFirst())
                .extracting(SeasonProgressDTO::totalEpisodeCount,
                        SeasonProgressDTO::watchedPercentage)
                .containsExactly(2, 100.0);
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Return Null Progress Fields - When TMDB Provides Zero Total Episodes")
    void shouldReturnNullProgressFieldsWhenTmdbProvidesZeroTotalEpisodesForSeriesInProgress() {
        DiaryEntryRepository.SeriesInProgress row = seriesInProgress(
                "1399", 3L, 8, 6, LocalDate.of(2024, 5, 1));
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(seriesProgressMetadataRefreshService.getSnapshotsForRead(eq(List.of("1399")), any()))
                .thenReturn(Map.of("1399", progressSnapshot("1399", null, List.of())));

        Page<SeriesInProgressResponseDTO> result = diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, 10);

        assertThat(result.getContent().getFirst())
                .extracting(SeriesInProgressResponseDTO::totalEpisodeCount,
                        SeriesInProgressResponseDTO::watchedPercentage)
                .containsExactly(null, null);
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Return Null Progress Fields - When TMDB Does Not Provide Total Episodes")
    void shouldReturnNullProgressFieldsWhenTmdbDoesNotProvideTotalEpisodesForSeriesInProgress() {
        DiaryEntryRepository.SeriesInProgress row = seriesInProgress(
                "1399", 3L, 8, 6, LocalDate.of(2024, 5, 1));
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(seriesProgressMetadataRefreshService.getSnapshotsForRead(eq(List.of("1399")), any()))
                .thenReturn(Map.of("1399", progressSnapshot("1399", null, List.of())));

        Page<SeriesInProgressResponseDTO> result = diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, 10);

        assertThat(result.getContent().getFirst())
                .extracting(SeriesInProgressResponseDTO::totalEpisodeCount,
                        SeriesInProgressResponseDTO::watchedPercentage)
                .containsExactly(null, null);
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Cap Watched Percentage At One Hundred - When Watched Episodes Exceed TMDB Total")
    void shouldCapWatchedPercentageAtOneHundredWhenWatchedEpisodesExceedTmdbTotalForSeriesInProgress() {
        DiaryEntryRepository.SeriesInProgress row = seriesInProgress(
                "1399", 12L, 8, 6, LocalDate.of(2024, 5, 1));
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(eq(lucasId), eq(List.of("1399"))))
                .thenReturn(List.of(seasonProgress("1399", 1, 12L, null)));
        when(seriesProgressMetadataRefreshService.getSnapshotsForRead(eq(List.of("1399")), any()))
                .thenReturn(Map.of("1399", progressSnapshot("1399", 10, List.of(
                        new SeriesProgressMetadataRefreshService.SeasonSnapshot(
                                "1399", 1, 10, null, null, LocalDate.of(2020, 1, 1), LocalDateTime.now())))));

        Page<SeriesInProgressResponseDTO> result = diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, 10);

        assertThat(result.getContent().getFirst().watchedPercentage()).isEqualTo(100.0);
        assertThat(result.getContent().getFirst().seasonProgress().getFirst().watchedPercentage()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Throw TmdbUnavailableException - When TMDB Is Unavailable")
    void shouldThrowTmdbUnavailableExceptionWhenTmdbIsUnavailableForSeriesInProgress() {
        DiaryEntryRepository.SeriesInProgress row = seriesInProgress(
                "1399", 3L, 8, 6, LocalDate.of(2024, 5, 1));
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(seriesProgressMetadataRefreshService.getSnapshotsForRead(eq(List.of("1399")), any()))
                .thenThrow(new TmdbUnavailableException("TMDB is currently unavailable"));

        assertThatThrownBy(() -> diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, 10))
                .isInstanceOf(TmdbUnavailableException.class)
                .hasMessage("TMDB is currently unavailable");
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Return Empty Page - When User Has No Series In Progress")
    void shouldReturnEmptyPageWhenUserHasNoSeriesInProgress() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        Page<SeriesInProgressResponseDTO> result = diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, 10);

        assertThat(result.getContent()).isEmpty();
        verify(seriesProgressMetadataRefreshService, never()).getSnapshotsForRead(any(), any());
        verify(diaryEntryRepository, never()).findWatchedEpisodeProgressByUserIdAndSeriesTmdbIds(any(), any());
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Throw NotFoundException - When User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenUserDoesNotExistForSeriesInProgress() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, 10))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verifyNoInteractions(diaryEntryRepository);
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Return Entries - When Target Profile Is Public And Viewer Is A Different User")
    void shouldReturnEntriesWhenTargetProfileIsPublicAndViewerIsADifferentUserForSeriesInProgress() {
        lucas.setIsProfilePublic(true);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        Page<SeriesInProgressResponseDTO> result = diaryEntryService.getSeriesInProgress(marinaId, lucasId, 1, 10);

        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(followerRepository);
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateAndViewerIsNotAnAcceptedFollowerForSeriesInProgress() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> diaryEntryService.getSeriesInProgress(marinaId, lucasId, 1, 10))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");

        verifyNoInteractions(diaryEntryRepository);
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPageWhenPageNumberIsNullForSeriesInProgress() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        diaryEntryService.getSeriesInProgress(lucasId, lucasId, null, 10);

        assertThat(captureRequestedProgressPage()).satisfies(page -> assertThat(page.getPageNumber()).isZero());
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Use Page Number Minus One - When Page Number Is Positive")
    void shouldUsePageNumberMinusOneWhenPageNumberIsPositiveForSeriesInProgress() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        diaryEntryService.getSeriesInProgress(lucasId, lucasId, 3, 10);

        assertThat(captureRequestedProgressPage()).satisfies(page -> assertThat(page.getPageNumber()).isEqualTo(2));
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Throw BadRequestException - When Page Number Is Negative")
    void shouldThrowBadRequestExceptionWhenPageNumberIsNegativeForSeriesInProgress() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getSeriesInProgress(lucasId, lucasId, -1, 10))
                .isInstanceOf(BadRequestException.class);

        verify(seriesProgressReadRepository, never()).findCandidatesByUserId(any(), any(), any(), any());
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Use Default Page Size - When Page Size Is Null")
    void shouldUseDefaultPageSizeWhenPageSizeIsNullForSeriesInProgress() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, null);

        assertThat(captureRequestedProgressPage())
                .satisfies(page -> assertThat(page.getPageSize()).isEqualTo(PageRequestFactory.DEFAULT_PAGE_SIZE));
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Clamp Page Size To Max Limit - When Page Size Exceeds Limit")
    void shouldClampPageSizeToMaxLimitWhenPageSizeExceedsLimitForSeriesInProgress() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, 1001);

        assertThat(captureRequestedProgressPage())
                .satisfies(page -> assertThat(page.getPageSize()).isEqualTo(PageRequestFactory.MAX_PAGE_SIZE));
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Throw BadRequestException - When Page Size Is Negative")
    void shouldThrowBadRequestExceptionWhenPageSizeIsNegativeForSeriesInProgress() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, -5))
                .isInstanceOf(BadRequestException.class);

        verify(seriesProgressReadRepository, never()).findCandidatesByUserId(any(), any(), any(), any());
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Throw BadRequestException - When Page Size Is Zero")
    void shouldThrowBadRequestExceptionWhenPageSizeIsZeroForSeriesInProgress() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, 0))
                .isInstanceOf(BadRequestException.class);

        verify(seriesProgressReadRepository, never()).findCandidatesByUserId(any(), any(), any(), any());
    }

    private DiaryEntryRepository.SeriesInProgress seriesInProgress(
            String seriesTmdbId, Integer maxSeasonNumber, Integer maxEpisodeNumber, LocalDate lastWatchedDate) {
        return seriesInProgress(seriesTmdbId, null, maxSeasonNumber, maxEpisodeNumber, lastWatchedDate);
    }

    private PageRequest captureRequestedProgressPage() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(seriesProgressReadRepository, times(2)).findCandidatesByUserId(
                eq(lucasId), any(SeriesProgressReadRepository.SeriesProgressSort.class),
                any(Sort.Direction.class), captor.capture());
        assertThat(captor.getAllValues().getFirst().isUnpaged()).isTrue();
        return (PageRequest) captor.getAllValues().get(1);
    }

    private Page<SeriesProgressReadRepository.SeriesProgressCandidate> candidatesPage(
            Pageable pageable, List<SeriesProgressReadRepository.SeriesProgressCandidate> candidates, boolean ascending) {
        if (pageable.isUnpaged()) {
            return new PageImpl<>(candidates);
        }
        int from = Math.min(pageable.getPageNumber() * pageable.getPageSize(), candidates.size());
        int to = Math.min(from + pageable.getPageSize(), candidates.size());
        return new PageImpl<>(candidates.subList(from, to), pageable, candidates.size());
    }

    private SeriesProgressReadRepository.SeriesProgressCandidate progressCandidate(
            String seriesTmdbId, Long watchedEpisodeCount, Long watchedRuntimeMinutes,
            Integer maxSeasonNumber, Integer maxEpisodeNumber, LocalDate lastWatchedDate,
            Integer lastWatchedSeasonNumber, Integer lastWatchedEpisodeNumber,
            Integer totalReleasedEpisodeCount, Integer totalKnownRuntime,
            LocalDate lastReleasedEpisodeDate, Long remainingEpisodeCount, Long remainingRuntimeMinutes) {
        return progressCandidate(seriesTmdbId, watchedEpisodeCount, watchedRuntimeMinutes,
                maxSeasonNumber, maxEpisodeNumber, lastWatchedDate, lastWatchedSeasonNumber,
                lastWatchedEpisodeNumber, totalReleasedEpisodeCount, totalKnownRuntime,
                lastReleasedEpisodeDate, remainingEpisodeCount, remainingRuntimeMinutes, true);
    }

    private SeriesProgressReadRepository.SeriesProgressCandidate progressCandidate(
            String seriesTmdbId, Long watchedEpisodeCount, Long watchedRuntimeMinutes,
            Integer maxSeasonNumber, Integer maxEpisodeNumber, LocalDate lastWatchedDate,
            Integer lastWatchedSeasonNumber, Integer lastWatchedEpisodeNumber,
            Integer totalReleasedEpisodeCount, Integer totalKnownRuntime,
            LocalDate lastReleasedEpisodeDate, Long remainingEpisodeCount, Long remainingRuntimeMinutes,
            boolean watchedRuntimeComplete) {
        return new SeriesProgressReadRepository.SeriesProgressCandidate() {
            @Override
            public String getSeriesTmdbId() {
                return seriesTmdbId;
            }

            @Override
            public Long getWatchedEpisodeCount() {
                return watchedEpisodeCount;
            }

            @Override
            public Long getWatchedRuntimeMinutes() {
                return watchedRuntimeMinutes;
            }

            @Override
            public Integer getMaxSeasonNumber() {
                return maxSeasonNumber;
            }

            @Override
            public Integer getMaxEpisodeNumber() {
                return maxEpisodeNumber;
            }

            @Override
            public LocalDate getLastWatchedDate() {
                return lastWatchedDate;
            }

            @Override
            public Integer getLastWatchedSeasonNumber() {
                return lastWatchedSeasonNumber;
            }

            @Override
            public Integer getLastWatchedEpisodeNumber() {
                return lastWatchedEpisodeNumber;
            }

            @Override
            public Integer getTotalReleasedEpisodeCount() {
                return totalReleasedEpisodeCount;
            }

            @Override
            public Integer getTotalKnownRuntime() {
                return totalKnownRuntime;
            }

            @Override
            public LocalDate getLastReleasedEpisodeDate() {
                return lastReleasedEpisodeDate;
            }

            @Override
            public Long getRemainingEpisodeCount() {
                return remainingEpisodeCount;
            }

            @Override
            public Long getRemainingRuntimeMinutes() {
                return remainingRuntimeMinutes;
            }

            @Override
            public Boolean getWatchedRuntimeComplete() {
                return watchedRuntimeComplete;
            }
        };
    }

    private SeriesProgressReadRepository.SeriesProgressTotals seriesProgressTotals(
            Long watchedEpisodeCount, Long watchedRuntimeMinutes) {
        return seriesProgressTotals(watchedEpisodeCount, watchedRuntimeMinutes, true);
    }

    private SeriesProgressReadRepository.SeriesProgressTotals seriesProgressTotals(
            Long watchedEpisodeCount, Long watchedRuntimeMinutes, boolean watchedRuntimeComplete) {
        return new SeriesProgressReadRepository.SeriesProgressTotals() {
            @Override
            public Long getWatchedEpisodeCount() {
                return watchedEpisodeCount;
            }

            @Override
            public Long getWatchedRuntimeMinutes() {
                return watchedRuntimeMinutes;
            }

            @Override
            public Boolean getWatchedRuntimeComplete() {
                return watchedRuntimeComplete;
            }
        };
    }

    private DiaryEntryRepository.SeasonProgress seasonProgress(
            String seriesTmdbId, Integer seasonNumber, Long watchedEpisodeCount, Long watchedRuntimeMinutes) {
        return seasonProgress(seriesTmdbId, seasonNumber, watchedEpisodeCount, watchedRuntimeMinutes, true);
    }

    private DiaryEntryRepository.SeasonProgress seasonProgress(
            String seriesTmdbId, Integer seasonNumber, Long watchedEpisodeCount, Long watchedRuntimeMinutes,
            boolean watchedRuntimeComplete) {
        return new DiaryEntryRepository.SeasonProgress() {
            @Override
            public String getSeriesTmdbId() {
                return seriesTmdbId;
            }

            @Override
            public Integer getSeasonNumber() {
                return seasonNumber;
            }

            @Override
            public Long getWatchedEpisodeCount() {
                return watchedEpisodeCount;
            }

            @Override
            public Long getWatchedRuntimeMinutes() {
                return watchedRuntimeMinutes;
            }

            @Override
            public Boolean getWatchedRuntimeComplete() {
                return watchedRuntimeComplete;
            }
        };
    }

    private SeriesProgressMetadataRefreshService.Snapshot snapshot(String seriesTmdbId) {
        Integer released = switch (seriesTmdbId) {
            case "a" -> 5;
            case "b" -> 4;
            default -> 3;
        };
        Integer runtime = "off-page".equals(seriesTmdbId) ? null : released * 20;
        return new SeriesProgressMetadataRefreshService.Snapshot(
                new SeriesProgressMetadataRefreshService.SeriesSnapshot(
                        seriesTmdbId, released, runtime, runtime == null ? 0 : released,
                        LocalDate.of(2026, 9, 22), LocalDateTime.now(), runtime == null ? null : LocalDateTime.now()),
                List.of(
                        new SeriesProgressMetadataRefreshService.SeasonSnapshot(
                                seriesTmdbId, 0, 1, 20, 1, LocalDate.of(2026, 9, 1), LocalDateTime.now()),
                        new SeriesProgressMetadataRefreshService.SeasonSnapshot(
                                seriesTmdbId, released == 3 ? 3 : 1, released, runtime, runtime == null ? 0 : released,
                                LocalDate.of(2026, 9, 22), LocalDateTime.now())));
    }

    private DiaryEntryRepository.SeriesInProgress seriesInProgress(
            String seriesTmdbId, Long watchedEpisodeCount, Integer maxSeasonNumber,
            Integer maxEpisodeNumber, LocalDate lastWatchedDate) {
        DiaryEntryRepository.SeriesInProgress row = new DiaryEntryRepository.SeriesInProgress() {
            @Override
            public String getSeriesTmdbId() {
                return seriesTmdbId;
            }

            @Override
            public Long getWatchedEpisodeCount() {
                return watchedEpisodeCount;
            }

            @Override
            public Integer getMaxSeasonNumber() {
                return maxSeasonNumber;
            }

            @Override
            public Integer getMaxEpisodeNumber() {
                return maxEpisodeNumber;
            }

            @Override
            public LocalDate getLastWatchedDate() {
                return lastWatchedDate;
            }
        };
        SeriesProgressReadRepository.SeriesProgressCandidate candidate = progressCandidate(
                seriesTmdbId, watchedEpisodeCount, null, maxSeasonNumber, maxEpisodeNumber, lastWatchedDate,
                maxSeasonNumber, maxEpisodeNumber, null, null, null, null, null);
        lenient().when(seriesProgressReadRepository.findCandidatesByUserId(
                        any(UUID.class), any(SeriesProgressReadRepository.SeriesProgressSort.class),
                        any(Sort.Direction.class), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(3);
                    return pageable.isUnpaged()
                            ? new PageImpl<>(List.of(candidate))
                            : new PageImpl<>(List.of(candidate), pageable, 1);
                });
        return row;
    }

    private SeriesProgressMetadataRefreshService.Snapshot progressSnapshot(
            String seriesTmdbId, Integer releasedEpisodeCount,
            List<SeriesProgressMetadataRefreshService.SeasonSnapshot> seasons) {
        return new SeriesProgressMetadataRefreshService.Snapshot(
                new SeriesProgressMetadataRefreshService.SeriesSnapshot(
                        seriesTmdbId, releasedEpisodeCount, null, null, null, LocalDateTime.now(), null),
                seasons);
    }

    private DiaryEntryRepository.SeasonProgressCount seasonProgressCount(
            String seriesTmdbId, Integer seasonNumber, Long watchedEpisodeCount) {
        return new DiaryEntryRepository.SeasonProgressCount() {
            @Override
            public String getSeriesTmdbId() {
                return seriesTmdbId;
            }

            @Override
            public Integer getSeasonNumber() {
                return seasonNumber;
            }

            @Override
            public Long getWatchedEpisodeCount() {
                return watchedEpisodeCount;
            }
        };
    }

    // ---------- getReviewsForContent ----------

    @Test
    @DisplayName("[getReviewsForContent] Should Throw NotFoundException - When Content Does Not Exist")
    void shouldThrowNotFoundExceptionWhenContentDoesNotExistForReviews() {
        UUID contentId = UUID.randomUUID();
        when(contentRepository.existsById(contentId)).thenReturn(false);

        assertThatThrownBy(() -> diaryEntryService.getReviewsForContent(lucasId, contentId, 1, 10))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Content not found");

        verify(diaryEntryRepository, never()).findContentReviewKeys(any(), any(), any());
    }

    @Test
    @DisplayName("[getReviewsForContent] Should Return Mapped Page - When Content Has Reviews Visible To The Viewer")
    void shouldReturnMappedPageWhenContentHasReviewsVisibleToTheViewer() {
        DiaryEntry entry = buildEntry(marina, fightClub);
        entry.setComment("Great movie");
        DiaryEntryResponseDTO dto = buildResponseDtoWithContent(entry);
        UserPreviewDTO reviewer = new UserPreviewDTO(marinaId, "marina", "marina.png", true);
        ContentCardDTO card = cardFor(fightClub, "Fight Club", ContentPreviewStatus.AVAILABLE);
        when(contentRepository.existsById(fightClub.getId())).thenReturn(true);
        DiaryEntryRepository.ContentReviewKey reviewKey = reviewKey(entry, ContentReviewSource.DIARY);
        when(diaryEntryRepository.findContentReviewKeys(eq(fightClub.getId()), eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(reviewKey)));
        when(diaryEntryRepository.findByIdInWithContentAndUser(List.of(entry.getId())))
                .thenReturn(List.of(entry));
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false, List.of())).thenReturn(dto);
        when(userMapper.userToUserPreviewDto(marina)).thenReturn(reviewer);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(ContentCoordinate.from(fightClub), card));

        Page<ContentReviewResponseDTO> result = diaryEntryService.getReviewsForContent(lucasId, fightClub.getId(), 1, 10);

        assertThat(result.getContent()).singleElement().satisfies(review -> {
            assertThat(review).isEqualTo(ContentReviewResponseDTO.fromDiary(dto, reviewer).withCard(card));
            assertThat(review.card()).isEqualTo(card);
            assertThat(review.comment()).isEqualTo("Great movie");
        });
        verify(contentCardAssembler).assemble(anyCollection(), cardContextCaptor.capture(), anySet());
        assertThat(cardContextCaptor.getValue().posterUserId()).isEqualTo(marinaId);
    }

    @Test
    @DisplayName("[getReviewsForContent] Should Preserve The Review Row - When Card Metadata Is Unavailable")
    void shouldPreserveTheReviewRowWhenCardMetadataIsUnavailable() {
        DiaryEntry entry = buildEntry(marina, fightClub);
        entry.setComment("Still visible");
        DiaryEntryResponseDTO dto = buildResponseDtoWithContent(entry);
        UserPreviewDTO reviewer = new UserPreviewDTO(marinaId, "marina", "marina.png", true);
        ContentCardDTO unavailableCard = cardFor(fightClub, null, ContentPreviewStatus.UNAVAILABLE);
        when(contentRepository.existsById(fightClub.getId())).thenReturn(true);
        DiaryEntryRepository.ContentReviewKey reviewKey = reviewKey(entry, ContentReviewSource.DIARY);
        when(diaryEntryRepository.findContentReviewKeys(eq(fightClub.getId()), eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(reviewKey)));
        when(diaryEntryRepository.findByIdInWithContentAndUser(List.of(entry.getId())))
                .thenReturn(List.of(entry));
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false, List.of())).thenReturn(dto);
        when(userMapper.userToUserPreviewDto(marina)).thenReturn(reviewer);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(ContentCoordinate.from(fightClub), unavailableCard));

        Page<ContentReviewResponseDTO> result = diaryEntryService.getReviewsForContent(lucasId, fightClub.getId(), 1, 10);

        assertThat(result.getContent()).singleElement().satisfies(review -> {
            assertThat(review.comment()).isEqualTo("Still visible");
            assertThat(review.card()).isEqualTo(unavailableCard);
            assertThat(review.card().previewStatus()).isEqualTo(ContentPreviewStatus.UNAVAILABLE);
        });
    }

    @Test
    @DisplayName("[getReviewsForContent] Should Preserve Known Runtime In The Card - When TMDB Metadata Is Partial")
    void shouldPreserveKnownRuntimeInTheCardWhenTmdbMetadataIsPartial() {
        DiaryEntry entry = buildEntry(marina, fightClub);
        entry.setComment("Runtime still known locally");
        entry.setLikesCount(2);
        DiaryEntryResponseDTO dto = buildResponseDtoWithRuntime(entry, 124);
        UserPreviewDTO reviewer = new UserPreviewDTO(marinaId, "marina", "marina.png", true);
        when(contentRepository.existsById(fightClub.getId())).thenReturn(true);
        DiaryEntryRepository.ContentReviewKey reviewKey = reviewKey(entry, ContentReviewSource.DIARY);
        when(diaryEntryRepository.findContentReviewKeys(eq(fightClub.getId()), eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(reviewKey)));
        when(diaryEntryRepository.findByIdInWithContentAndUser(List.of(entry.getId())))
                .thenReturn(List.of(entry));
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false, List.of())).thenReturn(dto);
        when(userMapper.userToUserPreviewDto(marina)).thenReturn(reviewer);
        when(commentPreviewAssembler.countDiaryEntries(List.of(entry.getId()), lucasId))
                .thenReturn(Map.of(entry.getId(), 3L));
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenAnswer(invocation -> {
                    List<ContentCardSpec> specs = invocation.getArgument(0);
                    ContentCardSpec spec = specs.get(0);
                    return Map.of(spec.coordinate(), new ContentCardDTO(
                            fightClub.getId(), ContentType.MOVIE, fightClub.getTmdbId(), null, null, null,
                            null, null, null, null, null, spec.runtimeMinutes(), null, null, null, null,
                            null, null, ContentPreviewStatus.PARTIAL));
                });

        Page<ContentReviewResponseDTO> result = diaryEntryService.getReviewsForContent(lucasId, fightClub.getId(), 1, 10);

        assertThat(result.getContent()).singleElement().satisfies(review -> {
            assertThat(review.card().runtimeMinutes()).isEqualTo(124);
            assertThat(review.comment()).isEqualTo("Runtime still known locally");
            assertThat(review.commentsCount()).isEqualTo(3L);
            assertThat(review.likesCount()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("[getReviewsForContent] Should Use The Popular Review Query - When Popular Sort Is Selected")
    void shouldUseThePopularReviewQueryWhenPopularSortIsSelected() {
        when(contentRepository.existsById(fightClub.getId())).thenReturn(true);
        when(diaryEntryRepository.findContentReviewKeys(
                eq(fightClub.getId()), eq(lucasId), eq(ContentReviewSort.POPULAR.name()), any(PageRequest.class)))
                .thenReturn(Page.empty());

        diaryEntryService.getReviewsForContent(lucasId, fightClub.getId(), ContentReviewSort.POPULAR, 1, 10);

        verify(diaryEntryRepository).findContentReviewKeys(
                eq(fightClub.getId()), eq(lucasId), eq(ContentReviewSort.POPULAR.name()), any(PageRequest.class));
        verify(diaryEntryRepository, never()).findContentReviewKeys(
                eq(fightClub.getId()), eq(lucasId), any(PageRequest.class));
    }

    @Test
    @DisplayName("[getReviewsForContent] Should Use Recent Sort - When Sort Is Null")
    void shouldUseRecentSortWhenSortIsNull() {
        when(contentRepository.existsById(fightClub.getId())).thenReturn(true);
        when(diaryEntryRepository.findContentReviewKeys(
                eq(fightClub.getId()), eq(lucasId), eq(ContentReviewSort.RECENT.name()), any(PageRequest.class)))
                .thenReturn(Page.empty());

        diaryEntryService.getReviewsForContent(lucasId, fightClub.getId(), null, 1, 10);

        verify(diaryEntryRepository).findContentReviewKeys(
                eq(fightClub.getId()), eq(lucasId), eq(ContentReviewSort.RECENT.name()), any(PageRequest.class));
    }

    @Test
    @DisplayName("[getReviewsForContent] Should Include Dropped Review With Its Source And Like State")
    void shouldIncludeDroppedReviewWithItsSourceAndLikeState() {
        UUID droppedEntryId = UUID.randomUUID();
        DroppedEntry droppedEntry = DroppedEntry.builder()
                .id(droppedEntryId)
                .user(marina)
                .content(fightClub)
                .type(ContentType.MOVIE)
                .comment("Stopped halfway")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .likesCount(2)
                .build();
        ContentReviewSource source = ContentReviewSource.DROPPED;
        DiaryEntryRepository.ContentReviewKey reviewKey = mock(DiaryEntryRepository.ContentReviewKey.class);
        when(reviewKey.getReviewId()).thenReturn(droppedEntryId);
        when(reviewKey.getSource()).thenReturn(source.name());
        ContentRefDTO contentRef = new ContentRefDTO(fightClub.getId(), "550", ContentType.MOVIE,
                null, null, null, null, null, null, null);
        when(contentRepository.existsById(fightClub.getId())).thenReturn(true);
        when(diaryEntryRepository.findContentReviewKeys(eq(fightClub.getId()), eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(reviewKey)));
        when(droppedEntryRepository.findByIdInWithUserAndContent(List.of(droppedEntryId)))
                .thenReturn(List.of(droppedEntry));
        when(contentMapper.contentToContentRefDto(fightClub)).thenReturn(contentRef);
        when(likeService.getLikedDroppedEntryIds(lucasId, List.of(droppedEntryId)))
                .thenReturn(Set.of(droppedEntryId));
        when(userMapper.userToUserPreviewDto(marina))
                .thenReturn(new UserPreviewDTO(marinaId, "marina", "marina.png", true));

        Page<ContentReviewResponseDTO> result = diaryEntryService.getReviewsForContent(
                lucasId, fightClub.getId(), 1, 10);

        assertThat(result.getContent()).singleElement().satisfies(review -> {
            assertThat(review.source()).isEqualTo(ContentReviewSource.DROPPED);
            assertThat(review.id()).isEqualTo(droppedEntryId);
            assertThat(review.comment()).isEqualTo("Stopped halfway");
            assertThat(review.likesCount()).isEqualTo(2);
            assertThat(review.likedByMe()).isTrue();
            assertThat(review.score()).isNull();
            assertThat(review.reviewer()).isEqualTo(new UserPreviewDTO(marinaId, "marina", "marina.png", true));
        });
    }

    @Test
    @DisplayName("[getReviewsForContent] Should Batch Posters By Author And Content - When Reviews Have Multiple Authors")
    void shouldBatchPostersByAuthorAndContentWhenReviewsHaveMultipleAuthors() {
        DiaryEntry firstEntry = buildEntry(lucas, fightClub);
        DiaryEntry secondEntry = buildEntry(marina, fightClub);
        DiaryEntryResponseDTO firstMapped = buildResponseDtoWithContent(firstEntry);
        DiaryEntryResponseDTO secondMapped = buildResponseDtoWithContent(secondEntry);
        String firstPoster = "https://image.tmdb.org/t/p/w342/lucas.png";
        String secondPoster = "https://image.tmdb.org/t/p/w342/marina.png";
        UserContentPosterService.UserContentPosterKey firstKey =
                new UserContentPosterService.UserContentPosterKey(lucasId, fightClub.getId());
        UserContentPosterService.UserContentPosterKey secondKey =
                new UserContentPosterService.UserContentPosterKey(marinaId, fightClub.getId());
        when(contentRepository.existsById(fightClub.getId())).thenReturn(true);
        DiaryEntryRepository.ContentReviewKey firstKeyRow = reviewKey(firstEntry, ContentReviewSource.DIARY);
        DiaryEntryRepository.ContentReviewKey secondKeyRow = reviewKey(secondEntry, ContentReviewSource.DIARY);
        when(diaryEntryRepository.findContentReviewKeys(eq(fightClub.getId()), eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(firstKeyRow, secondKeyRow)));
        when(diaryEntryRepository.findByIdInWithContentAndUser(List.of(firstEntry.getId(), secondEntry.getId())))
                .thenReturn(List.of(firstEntry, secondEntry));
        when(diaryEntryMapper.diaryEntryToResponseDto(firstEntry, false, List.of())).thenReturn(firstMapped);
        when(diaryEntryMapper.diaryEntryToResponseDto(secondEntry, false, List.of())).thenReturn(secondMapped);
        when(userContentPosterService.findByUserAndContentPairs(List.of(firstKey, secondKey)))
                .thenReturn(Map.of(firstKey, firstPoster, secondKey, secondPoster));

        Page<ContentReviewResponseDTO> result = diaryEntryService.getReviewsForContent(
                lucasId, fightClub.getId(), 1, 10);

        assertThat(result.getContent()).extracting(ContentReviewResponseDTO::customPosterUrl)
                .containsExactly(firstPoster, secondPoster);
        verify(userContentPosterService).findByUserAndContentPairs(List.of(firstKey, secondKey));
        verify(userContentPosterService, never()).findByUserAndContentIds(any(), any());
        verify(contentCardAssembler, times(2)).assemble(anyCollection(), any(ContentCardContext.class), anySet());
    }

    @Test
    @DisplayName("[getReviewsForContent] Should Return Empty Page - When Content Has No Visible Reviews")
    void shouldReturnEmptyPageWhenContentHasNoVisibleReviews() {
        when(contentRepository.existsById(fightClub.getId())).thenReturn(true);
        when(diaryEntryRepository.findContentReviewKeys(eq(fightClub.getId()), eq(lucasId), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<ContentReviewResponseDTO> result = diaryEntryService.getReviewsForContent(lucasId, fightClub.getId(), 1, 10);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[getReviewsForContent] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPageWhenPageNumberIsNullForReviews() {
        when(contentRepository.existsById(fightClub.getId())).thenReturn(true);
        when(diaryEntryRepository.findContentReviewKeys(eq(fightClub.getId()), eq(lucasId), any(PageRequest.class)))
                .thenReturn(Page.empty());

        diaryEntryService.getReviewsForContent(lucasId, fightClub.getId(), null, 10);

        verify(diaryEntryRepository).findContentReviewKeys(eq(fightClub.getId()), eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isZero();
    }

    // ---------- createDiaryEntry ----------

    @Test
    @DisplayName("[createDiaryEntry] Should Save Entry With Resolved Content And All Provided Fields")
    void shouldSaveEntryWithResolvedContentAndAllProvidedFields() {
        stubContentResolution(fightClub);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        DiaryEntry savedEntry = buildEntry(lucas, fightClub);
        DiaryEntryResponseDTO expectedDto = buildResponseDto(savedEntry);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(expectedDto);
        LocalDate watchedDate = LocalDate.of(2024, 5, 1);

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null),
                "Great movie", 9, watchedDate, true, false, "https://image.tmdb.org/t/p/w342/poster.png");

        DiaryEntryCreationResultDTO result = diaryEntryService.createDiaryEntry(lucasId, dto);

        assertThat(result.entry()).isEqualTo(expectedDto);
        verify(diaryEntryRepository).saveAndFlush(entryCaptor.capture());
        DiaryEntry captured = entryCaptor.getValue();
        assertThat(captured.getUser()).isEqualTo(lucas);
        assertThat(captured.getContent()).isEqualTo(fightClub);
        assertThat(captured.getComment()).isEqualTo("Great movie");
        assertThat(captured.getScore()).isEqualTo(9);
        assertThat(captured.getWatchedDate()).isEqualTo(watchedDate);
        assertThat(captured.getWatchNumber()).isEqualTo(2);
        assertThat(captured.getWatchedInTheater()).isFalse();
        verify(userContentPosterService).upsert(lucasId, fightClub.getId(), "https://image.tmdb.org/t/p/w342/poster.png");
        assertThat(captured.getIgnore()).isFalse();
        verify(contentService).getOrCreateReference(contentRefCreationCaptor.capture(), anyBoolean());
        assertThat(contentRefCreationCaptor.getValue())
                .isEqualTo(new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null));
        InOrder mutationOrder = inOrder(contentService, userContentMutationLock, diaryWatchCommandService, diaryEntryRepository);
        mutationOrder.verify(contentService).getOrCreateReference(any(ContentRefCreationDTO.class), anyBoolean());
        mutationOrder.verify(userContentMutationLock).lock(lucasId);
        mutationOrder.verify(diaryWatchCommandService).recordPrepared(eq(lucasId), any(WatchEventCommand.class));
        mutationOrder.verify(diaryEntryRepository).saveAndFlush(any(DiaryEntry.class));
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Derive IsSeasonFinale True - When Episode Number Matches The Season's Latest Aired Episode From TMDB")
    void shouldDeriveIsSeasonFinaleTrueWhenEpisodeNumberMatchesTheSeasonsLatestAiredEpisodeFromTmdb() {
        Content episode = buildEpisode("1399", 1, 2);
        stubContentResolution(episode);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(episode.getId())).thenReturn(episode);
        DiaryEntry savedEntry = buildEntry(lucas, episode);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));
        when(tmdbClient.getSeasonFullDetails(eq("1399"), eq(1), any())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSeasonFullDetails(null, null, null, null, null, null,
                        List.of(new TmdbEpisodeSummary(1, null, null, "2020-01-01", null, null, null),
                                new TmdbEpisodeSummary(2, null, null, "2020-01-08", null, null, null)),
                        null, null)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 2, null, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(contentService).getOrCreateReference(contentRefCreationCaptor.capture(), anyBoolean());
        assertThat(contentRefCreationCaptor.getValue().isSeasonFinale()).isTrue();
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Override IsSeasonFinale To Null - When Episode Number Is Before The Season's Latest Aired Episode From TMDB")
    void shouldOverrideIsSeasonFinaleToNullWhenEpisodeNumberIsBeforeTheSeasonsLatestAiredEpisodeFromTmdb() {
        Content episode = buildEpisode("1399", 1, 1);
        stubContentResolution(episode);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(episode.getId())).thenReturn(episode);
        DiaryEntry savedEntry = buildEntry(lucas, episode);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));
        when(tmdbClient.getSeasonFullDetails(eq("1399"), eq(1), any())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSeasonFullDetails(null, null, null, null, null, null,
                        List.of(new TmdbEpisodeSummary(1, null, null, "2020-01-01", null, null, null),
                                new TmdbEpisodeSummary(2, null, null, "2020-01-08", null, null, null)),
                        null, null)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 1, true, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(contentService).getOrCreateReference(contentRefCreationCaptor.capture(), anyBoolean());
        assertThat(contentRefCreationCaptor.getValue().isSeasonFinale()).isNull();
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Derive IsSeriesFinale True - When The Season Is Also The Series' Latest Aired Season From TMDB")
    void shouldDeriveIsSeriesFinaleTrueWhenTheSeasonIsAlsoTheSeriesLatestAiredSeasonFromTmdb() {
        Content episode = buildEpisode("1399", 4, 3);
        stubContentResolution(episode);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(episode.getId())).thenReturn(episode);
        DiaryEntry savedEntry = buildEntry(lucas, episode);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));
        when(tmdbClient.getSeasonFullDetails(eq("1399"), eq(4), any())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSeasonFullDetails(null, null, null, null, null, null,
                        List.of(new TmdbEpisodeSummary(1, null, null, "2020-01-01", null, null, null),
                                new TmdbEpisodeSummary(2, null, null, "2020-01-08", null, null, null),
                                new TmdbEpisodeSummary(3, null, null, "2020-01-15", null, null, null)),
                        null, null)));
        when(tmdbClient.getTvFullDetails(eq("1399"), any())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbTvFullDetails(null, null, null, null, null, null, null, null, null, null, null,
                        List.of(new TmdbSeasonSummary(3, null, null, "2019-01-01", 10, null),
                                new TmdbSeasonSummary(4, null, null, "2020-01-01", 3, null)),
                        null, null, null, null, null, null, null, null, null)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 4, 3, null, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(contentService).getOrCreateReference(contentRefCreationCaptor.capture(), anyBoolean());
        assertThat(contentRefCreationCaptor.getValue().isSeasonFinale()).isTrue();
        assertThat(contentRefCreationCaptor.getValue().isSeriesFinale()).isTrue();
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Fall Back To Client-Supplied IsSeasonFinale - When TMDB Has No Aired Episodes For The Season")
    void shouldFallBackToClientSuppliedIsSeasonFinaleWhenTmdbHasNoAiredEpisodesForTheSeason() {
        Content episode = buildEpisode("1399", 1, 5);
        stubContentResolution(episode);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(episode.getId())).thenReturn(episode);
        DiaryEntry savedEntry = buildEntry(lucas, episode);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 5, true, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(contentService).getOrCreateReference(contentRefCreationCaptor.capture(), anyBoolean());
        assertThat(contentRefCreationCaptor.getValue().isSeasonFinale()).isTrue();
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Pass Trusted RuntimeMinutes Derived From The Season Payload - When Creating A New Episode")
    void shouldPassTrustedRuntimeMinutesDerivedFromTheSeasonPayloadWhenCreatingANewEpisode() {
        Content episode = buildEpisode("1399", 1, 2);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(episode.getId())).thenReturn(episode);
        DiaryEntry savedEntry = buildEntry(lucas, episode);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));
        when(tmdbClient.getSeasonFullDetails(eq("1399"), eq(1), any())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSeasonFullDetails(null, null, null, null, null, null,
                        List.of(new TmdbEpisodeSummary(1, null, null, "2020-01-01", 30, null, null),
                                new TmdbEpisodeSummary(2, null, null, "2020-01-08", 42, null, null)),
                        null, null)));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenReturn(new ContentRefDTO(episode.getId(), null, ContentType.EPISODE, "1399", 1, 2, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 2, null, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(contentService).getOrCreateReference(contentRefCreationCaptor.capture(), eq(true));
        assertThat(contentRefCreationCaptor.getValue().runtimeMinutes()).isEqualTo(42);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Pass Untrusted Null RuntimeMinutes - When The Season Payload Has No Runtime For The Episode")
    void shouldPassUntrustedNullRuntimeMinutesWhenTheSeasonPayloadHasNoRuntimeForTheEpisode() {
        Content episode = buildEpisode("1399", 1, 2);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(episode.getId())).thenReturn(episode);
        DiaryEntry savedEntry = buildEntry(lucas, episode);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));
        when(tmdbClient.getSeasonFullDetails(eq("1399"), eq(1), any())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSeasonFullDetails(null, null, null, null, null, null,
                        List.of(new TmdbEpisodeSummary(1, null, null, "2020-01-01", 30, null, null),
                                new TmdbEpisodeSummary(2, null, null, "2020-01-08", null, null, null)),
                        null, null)));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(false)))
                .thenReturn(new ContentRefDTO(episode.getId(), null, ContentType.EPISODE, "1399", 1, 2, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 2, null, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(contentService).getOrCreateReference(contentRefCreationCaptor.capture(), eq(false));
        assertThat(contentRefCreationCaptor.getValue().runtimeMinutes()).isNull();
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Check Movie Release Date In The Language-Independent Lookup Language - When User Prefers A Different Language")
    void shouldCheckMovieReleaseDateInTheLanguageIndependentLookupLanguageWhenUserPrefersADifferentLanguage() {
        User lucasWithNonDefaultLanguage = User.builder()
                .id(lucasId)
                .username(lucas.getUsername())
                .email(lucas.getEmail())
                .password(lucas.getPassword())
                .isProfilePublic(lucas.getIsProfilePublic())
                .preferredLanguage("pt-BR")
                .createdAt(lucas.getCreatedAt())
                .updatedAt(lucas.getUpdatedAt())
                .build();
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucasWithNonDefaultLanguage));
        stubContentResolution(fightClub);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        DiaryEntry savedEntry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null),
                null, null, LocalDate.of(2024, 5, 1), null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(tmdbClient).getMovieFullDetails(fightClub.getTmdbId(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE);
        verify(tmdbClient, never()).getMovieFullDetails(fightClub.getTmdbId(), "pt-BR");
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Throw BadRequestException - When WatchedDate Is Before The Episode's Air Date From TMDB")
    void shouldThrowBadRequestExceptionWhenWatchedDateIsBeforeTheEpisodesAirDateFromTmdb() {
        when(tmdbClient.getSeasonFullDetails(eq("1399"), eq(1), any())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSeasonFullDetails(null, null, null, null, null, null,
                        List.of(new TmdbEpisodeSummary(2, null, null, "2030-01-01", null, null, null)),
                        null, null)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 2, null, null),
                null, null, LocalDate.of(2024, 5, 1), null, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntry(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("watchedDate cannot predate the content's release date");

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Throw BadRequestException - When WatchedDate Is Before The Movie's Release Date From TMDB")
    void shouldThrowBadRequestExceptionWhenWatchedDateIsBeforeTheMoviesReleaseDateFromTmdb() {
        when(tmdbClient.getMovieFullDetails(eq(fightClub.getTmdbId()), any())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbMovieFullDetails(null, null, null, null, null, null, "2030-01-01", null, null, null,
                        null, null, null, null, null, null, null)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null),
                null, null, LocalDate.of(2024, 5, 1), null, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntry(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("watchedDate cannot predate the content's release date");

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Throw BadRequestException - When WatchedWith Includes The Owner")
    void shouldThrowBadRequestExceptionWhenWatchedWithIncludesTheOwner() {
        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null),
                null, null, null, null, null, null, List.of(lucasId));

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntry(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("watchedWith cannot include yourself");
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Throw BadRequestException - When WatchedWith Includes A User Not Followed")
    void shouldThrowBadRequestExceptionWhenWatchedWithIncludesAUserNotFollowed() {
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(false);
        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null),
                null, null, null, null, null, null, List.of(marinaId));

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntry(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("watchedWith can only include users you follow");
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Pass WatchCompanions To The Command - When A Followed Companion Is Provided")
    void shouldPassWatchCompanionsToTheCommandWhenAFollowedCompanionIsProvided() {
        stubContentResolution(fightClub);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(userRepository.getReferenceById(marinaId)).thenReturn(marina);
        DiaryEntry savedEntry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(eq(savedEntry), eq(false), any()))
                .thenReturn(buildResponseDto(savedEntry));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null),
                null, null, null, null, null, null, List.of(marinaId, marinaId));

        diaryEntryService.createDiaryEntry(lucasId, dto);

        ArgumentCaptor<WatchEventCommand> commandCaptor = ArgumentCaptor.forClass(WatchEventCommand.class);
        verify(diaryWatchCommandService).recordPrepared(eq(lucasId), commandCaptor.capture());
        assertThat(commandCaptor.getValue().watchedWith()).containsExactly(marinaId);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Set WatchNumber To 1 - When No Prior Entry Exists And IsRewatch Is Not Requested")
    void shouldSetWatchNumberToOneWhenNoPriorEntryExistsAndIsRewatchIsNotRequested() {
        stubContentResolution(fightClub);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, fightClub.getId())).thenReturn(0);
        DiaryEntry savedEntry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryEntryRepository).saveAndFlush(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getWatchNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Set WatchNumber To 2 - When No Prior Entry Exists But IsRewatch Is True")
    void shouldSetWatchNumberToTwoWhenNoPriorEntryExistsButIsRewatchIsTrue() {
        stubContentResolution(fightClub);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, fightClub.getId())).thenReturn(0);
        DiaryEntry savedEntry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null),
                null, null, null, true, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryEntryRepository).saveAndFlush(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getWatchNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Set WatchNumber To MaxPlusOne Ignoring IsRewatch - When A Prior Entry Already Exists")
    void shouldSetWatchNumberToMaxPlusOneIgnoringIsRewatchWhenAPriorEntryAlreadyExists() {
        stubContentResolution(fightClub);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, fightClub.getId())).thenReturn(2);
        DiaryEntry savedEntry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null),
                null, null, null, false, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryEntryRepository).saveAndFlush(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getWatchNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Ignore IsRewatch And Set WatchNumber To 1 - When No Prior Entry Exists And Content Type Is EPISODE")
    void shouldIgnoreIsRewatchAndSetWatchNumberToOneWhenNoPriorEntryExistsAndContentTypeIsEpisode() {
        Content episode = buildEpisode("900", 1, 1);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), anyBoolean()))
                .thenReturn(new ContentRefDTO(episode.getId(), null, ContentType.EPISODE, "900", 1, 1, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(episode.getId())).thenReturn(episode);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, episode.getId())).thenReturn(0);
        DiaryEntry savedEntry = buildEntry(lucas, episode);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "900", 1, 1, null, null),
                null, null, null, true, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryEntryRepository).saveAndFlush(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getWatchNumber()).isEqualTo(1);
        ArgumentCaptor<WatchEventCommand> commandCaptor = ArgumentCaptor.forClass(WatchEventCommand.class);
        verify(diaryWatchCommandService).recordPrepared(eq(lucasId), commandCaptor.capture());
        assertThat(commandCaptor.getValue().requestedWatchNumber()).isNull();
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Ignore IsRewatch And Set WatchNumber To 1 - When No Prior Entry Exists And Content Type Is SEASON")
    void shouldIgnoreIsRewatchAndSetWatchNumberToOneWhenNoPriorEntryExistsAndContentTypeIsSeason() {
        Content season = buildSeason("900", 1);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), anyBoolean()))
                .thenReturn(new ContentRefDTO(season.getId(), null, ContentType.SEASON, "900", 1, null, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        lenient().when(contentRepository.getReferenceById(season.getId())).thenReturn(season);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, season.getId())).thenReturn(0);
        DiaryEntry savedEntry = buildEntry(lucas, season);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null),
                null, null, null, true, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntry(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Diary event logging only supports content of type MOVIE or EPISODE");
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Honor IsRewatch And Set WatchNumber To 2 - When No Prior Entry Exists But Content Type Is SERIES")
    void shouldHonorIsRewatchAndSetWatchNumberToTwoWhenNoPriorEntryExistsButContentTypeIsSeries() {
        Content series = buildContent("900", ContentType.SERIES);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), anyBoolean()))
                .thenReturn(new ContentRefDTO(series.getId(), "900", ContentType.SERIES, null, null, null, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        lenient().when(contentRepository.getReferenceById(series.getId())).thenReturn(series);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, series.getId())).thenReturn(0);
        DiaryEntry savedEntry = buildEntry(lucas, series);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null),
                null, null, null, true, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntry(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Diary event logging only supports content of type MOVIE or EPISODE");
    }

    // ---------- createDiaryEntry: watchlist/dropped removal side effect ----------

    @Test
    @DisplayName("[createDiaryEntry] Should Remove The Matching Watchlist Entry - When Logging A Movie")
    void shouldRemoveTheMatchingWatchlistEntryWhenLoggingAMovie() {
        stubContentResolution(fightClub);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        DiaryEntry savedEntry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryWatchCommandService).recordPrepared(eq(lucasId), any(WatchEventCommand.class));
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Delete The Dropped Entry - When One Exists For The Logged Movie")
    void shouldDeleteTheDroppedEntryWhenOneExistsForTheLoggedMovie() {
        stubContentResolution(fightClub);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        DiaryEntry savedEntry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));
        DroppedEntry droppedEntry = DroppedEntry.builder().id(UUID.randomUUID()).build();
        when(droppedEntryRepository.findByUserIdAndTypeAndContentId(lucasId, ContentType.MOVIE, fightClub.getId()))
                .thenReturn(Optional.of(droppedEntry));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryWatchCommandService).recordPrepared(eq(lucasId), any(WatchEventCommand.class));
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Resolve The Series And Remove Its Watchlist And Dropped Entries - When Logging An Episode")
    void shouldResolveTheSeriesAndRemoveItsWatchlistAndDroppedEntriesWhenLoggingAnEpisode() {
        Content episode = buildEpisode("900", 1, 1);
        Content seriesContent = buildContent("900", ContentType.SERIES);

        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), anyBoolean()))
                .thenReturn(new ContentRefDTO(episode.getId(), null, ContentType.EPISODE, "900", 1, 1, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(episode.getId())).thenReturn(episode);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, episode.getId())).thenReturn(0);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, episode)));
        when(contentRepository.findByTmdbIdAndType("900", ContentType.SERIES)).thenReturn(Optional.of(seriesContent));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "900", 1, 1, null, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryWatchCommandService).recordPrepared(eq(lucasId), any(WatchEventCommand.class));
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Not Attempt Removal - When The Series Content Does Not Exist Yet")
    void shouldNotAttemptRemovalWhenTheSeriesContentDoesNotExistYet() {
        Content episode = buildEpisode("900", 1, 1);

        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), anyBoolean()))
                .thenReturn(new ContentRefDTO(episode.getId(), null, ContentType.EPISODE, "900", 1, 1, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(episode.getId())).thenReturn(episode);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, episode.getId())).thenReturn(0);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, episode)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "900", 1, 1, null, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(watchlistEntryService, never()).removeEntryIfPresent(any(), any(), any());
        verify(droppedEntryRepository, never()).findByUserIdAndTypeAndContentId(any(), any(), any());
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Remove The Matching Watchlist Entry - When Logging A Series Directly")
    void shouldRemoveTheMatchingWatchlistEntryWhenLoggingASeriesDirectly() {
        Content seriesContent = buildContent("900", ContentType.SERIES);

        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), anyBoolean()))
                .thenReturn(new ContentRefDTO(seriesContent.getId(), "900", ContentType.SERIES, null, null, null, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(seriesContent.getId())).thenReturn(seriesContent);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, seriesContent.getId())).thenReturn(0);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, seriesContent)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null),
                null, null, null, null, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntry(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Diary event logging only supports content of type MOVIE or EPISODE");
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Throw ConflictException - When Save Fails On The Watch Number Unique Constraint")
    void shouldThrowConflictExceptionWhenSaveFailsOnTheWatchNumberUniqueConstraint() {
        stubContentResolution(fightClub);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, fightClub.getId())).thenReturn(0);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_diary_entries_user_content_watch_number"));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null),
                null, null, null, null, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntry(lucasId, dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This watch was already logged by a concurrent request");
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Throw ConflictException With Generic Message - When Constraint Name Is Unknown")
    void shouldThrowConflictExceptionWithGenericMessageWhenConstraintNameIsUnknown() {
        stubContentResolution(fightClub);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, fightClub.getId())).thenReturn(0);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class)))
                .thenThrow(buildDataIntegrityViolationException("some_other_constraint"));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null),
                null, null, null, null, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntry(lucasId, dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Diary entry could not be saved due to a conflict");
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Throw BadRequestException - When WatchedInTheater Is Set And Content Type Is Not Movie")
    void shouldThrowBadRequestExceptionWhenWatchedInTheaterIsSetAndContentTypeIsNotMovieOnCreate() {
        Content theOffice = buildContent("2316", ContentType.SERIES);
        stubContentResolution(theOffice);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(theOffice.getId())).thenReturn(theOffice);

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(theOffice.getTmdbId(), ContentType.SERIES, null, null, null, null, null),
                null, null, null, null, true, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntry(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("watchedInTheater can only be set for content of type MOVIE");

        verify(diaryEntryRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Throw BadRequestException - When WatchedDate Is In The Future")
    void shouldThrowBadRequestExceptionWhenWatchedDateIsInTheFutureOnCreate() {
        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO("550", ContentType.MOVIE, null, null, null, null, null),
                null, null, LocalDate.now().plusDays(1), null, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntry(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("watchedDate cannot be in the future");

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Return Only The Prepared Episode Event - When Another Episode Is The Series Finale")
    void shouldReturnOnlyThePreparedEpisodeEventWhenAnotherEpisodeIsTheSeriesFinale() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 2);
        finaleEpisode.setIsSeriesFinale(true);
        Content nonFinaleEpisode = buildEpisode("1399", 1, 1);

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(nonFinaleEpisode);
        when(contentRepository.getReferenceById(nonFinaleEpisode.getId())).thenReturn(nonFinaleEpisode);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, nonFinaleEpisode.getId())).thenReturn(0);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(invocation -> buildResponseDtoWithContent(invocation.getArgument(0)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 1, null, null),
                null, null, LocalDate.of(2024, 5, 1), null, null, null);

        DiaryEntryCreationResultDTO result = diaryEntryService.createDiaryEntry(lucasId, dto);

        assertThat(result.events()).hasSize(1);
        assertThat(result.events().getFirst().entry().content().type()).isEqualTo(ContentType.EPISODE);
        assertThat(result.completions()).isEmpty();
        verify(diaryWatchCommandService).recordPrepared(
                eq(lucasId), eq(new WatchEventCommand(
                        MediaReference.episode("1399", 1, 1), LocalDate.of(2024, 5, 1), null,
                        null, null, null, List.of(), null)));
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Map A Watch Number Conflict - When The Event Cannot Be Saved")
    void shouldMapAWatchNumberConflictWhenTheEventCannotBeSaved() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 1);
        Content season = buildSeason("1399", 1);
        DataIntegrityViolationException exception = buildDataIntegrityViolationException("uq_diary_entries_user_content_watch_number");

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(finaleEpisode);
        when(contentRepository.getReferenceById(finaleEpisode.getId())).thenReturn(finaleEpisode);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, finaleEpisode.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.findEpisodeEntriesByUserIdAndSeriesTmdbIdAndSeasonNumber(lucasId, "1399", 1))
                .thenReturn(List.of(buildDiaryEntry(lucas, finaleEpisode, 1)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, null)))
                .thenReturn(new ContentRefDTO(season.getId(), null, ContentType.SEASON, "1399", 1, null, null, null, null, null));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType(
                "1399", 1, null, ContentType.SEASON)).thenReturn(Optional.of(season));
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenThrow(exception);

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 1, true, null),
                null, null, LocalDate.of(2024, 5, 1), null, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntry(lucasId, dto))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This watch was already logged by a concurrent request");
        verifyNoInteractions(newTransactionExecutor);
    }

    // ---------- createDiaryEntriesInBulk ----------

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Throw BadRequestException - When Content Type Is Not SEASON Or SERIES")
    void shouldThrowBadRequestExceptionWhenContentTypeIsNotSeasonOrSeries() {
        ContentRefCreationDTO movieRef = new ContentRefCreationDTO("100", ContentType.MOVIE, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(movieRef, LocalDate.now(), null, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Bulk logging only supports content of type SEASON or SERIES");
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Throw BadRequestException - When A SEASON Bulk Request Has Genres, ReleaseYear Or Countries")
    void shouldThrowBadRequestExceptionWhenSeasonBulkRequestHasGenresReleaseYearOrCountries() {
        ContentRefCreationDTO seasonRefWithGenres = new ContentRefCreationDTO(
                null, ContentType.SEASON, "900", 1, null, null, null, null, List.of("Comedy"), null, null);
        DiaryEntryBulkCreationDTO dtoWithGenres = new DiaryEntryBulkCreationDTO(seasonRefWithGenres, LocalDate.now(), null, null, null);

        ContentRefCreationDTO seasonRefWithReleaseYear = new ContentRefCreationDTO(
                null, ContentType.SEASON, "900", 1, null, null, null, null, null, 2005, null);
        DiaryEntryBulkCreationDTO dtoWithReleaseYear = new DiaryEntryBulkCreationDTO(seasonRefWithReleaseYear, LocalDate.now(), null, null, null);

        ContentRefCreationDTO seasonRefWithCountries = new ContentRefCreationDTO(
                null, ContentType.SEASON, "900", 1, null, null, null, null, null, null, List.of("US"));
        DiaryEntryBulkCreationDTO dtoWithCountries = new DiaryEntryBulkCreationDTO(seasonRefWithCountries, LocalDate.now(), null, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dtoWithGenres))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("genres, releaseYear and countries must not be provided when bulk logging a SEASON");
        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dtoWithReleaseYear))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("genres, releaseYear and countries must not be provided when bulk logging a SEASON");
        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dtoWithCountries))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("genres, releaseYear and countries must not be provided when bulk logging a SEASON");
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Throw BadRequestException - When SEASON Has No Existing Finale And No FinaleEpisodeNumber Is Provided")
    void shouldThrowBadRequestExceptionWhenSeasonHasNoExistingFinaleAndNoFinaleEpisodeNumberIsProvided() {
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.now(), null, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("finaleEpisodeNumber is required");
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Throw BadRequestException - When WatchedDate Is In The Future")
    void shouldThrowBadRequestExceptionWhenWatchedDateIsInTheFutureOnBulk() {
        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.now().plusDays(1), 2, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("watchedDate cannot be in the future");

        verifyNoInteractions(tmdbClient);
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Throw BadRequestException - When WatchedDate Predates All Season Episodes")
    void shouldThrowBadRequestExceptionWhenWatchedDatePredatesAllSeasonEpisodes() {
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSeasonFullDetails(null, null, null, null, null, null, List.of(
                        new TmdbEpisodeSummary(1, null, null, "2020-01-01", 45, null, null),
                        new TmdbEpisodeSummary(2, null, null, "2020-01-08", 45, null, null)),
                        null, null)));

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.of(2019, 12, 31), 2, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("No episodes in season 1 have been released by 2019-12-31");

        verify(contentService, never()).getOrCreateReference(any());
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Reject A SEASON Bulk - When Existing Finale Date Is Unknown And Other Episodes Are Future")
    void shouldRejectSeasonBulkWhenExistingFinaleDateIsUnknownAndOtherEpisodesAreFuture() {
        Content existingFinale = buildFinaleEpisode("900", 1, 1);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(existingFinale));
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSeasonFullDetails(null, null, null, null, null, null, List.of(
                        new TmdbEpisodeSummary(1, null, null, null, 45, null, null),
                        new TmdbEpisodeSummary(2, null, null, "2026-09-22", 45, null, null)),
                        null, null)));

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(
                seasonRef, LocalDate.of(2026, 9, 21), 2, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("No episodes in season 1 have been released by 2026-09-21");

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Keep A Later Global Finale Unchanged - When A Historical Season Bulk Ends Earlier")
    void shouldKeepLaterGlobalFinaleUnchangedWhenHistoricalSeasonBulkEndsEarlier() {
        Content e1 = buildEpisode("900", 1, 1);
        Content e2 = buildEpisode("900", 1, 2);
        Content globalFinale = buildFinaleEpisode("900", 1, 3);
        Content historicalSeason = buildSeason("900", 1);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(globalFinale));
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSeasonFullDetails(null, null, null, null, null, null, List.of(
                        new TmdbEpisodeSummary(1, null, null, "2026-09-20", 45, null, null),
                        new TmdbEpisodeSummary(2, null, null, "2026-09-21", 45, null, null),
                        new TmdbEpisodeSummary(3, null, null, "2026-09-22", 45, null, null)), null, null)));
        when(diaryEntryRepository.findEpisodeEntriesByUserIdAndSeriesTmdbIdAndSeasonNumber(lucasId, "900", 1))
                .thenReturn(
                        List.of(),
                        List.of(buildDiaryEntry(lucas, e1, 1), buildDiaryEntry(lucas, e2, 1)));
        when(diaryEntryRepository.findFirstByUserIdAndContentIdAndWatchNumber(lucasId, historicalSeason.getId(), 1))
                .thenReturn(Optional.empty());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true))).thenAnswer(inv -> {
            ContentRefCreationDTO ref = inv.getArgument(0);
            Content episode = ref.episodeNumber() == 1 ? e1 : e2;
            return new ContentRefDTO(episode.getId(), null, ContentType.EPISODE, "900", 1, ref.episodeNumber(), null, null, null, null);
        });
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class))).thenReturn(new ContentRefDTO(
                historicalSeason.getId(), null, ContentType.SEASON, "900", 1, null, null, null, null, null));
        when(contentRepository.getReferenceById(e1.getId())).thenReturn(e1);
        when(contentRepository.getReferenceById(e2.getId())).thenReturn(e2);
        lenient().when(contentRepository.getReferenceById(historicalSeason.getId())).thenReturn(historicalSeason);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(watchCompanionRepository.findByDiaryEntryIdIn(any())).thenReturn(List.of());
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenAnswer(inv -> {
            DiaryEntry entry = inv.getArgument(0);
            Content entryContent = entry.getContent();
            return new DiaryEntryResponseDTO(entry.getId(), entry.getUser().getId(),
                    new ContentRefDTO(entryContent.getId(), entryContent.getTmdbId(), entryContent.getType(),
                            entryContent.getSeriesTmdbId(), entryContent.getSeasonNumber(), entryContent.getEpisodeNumber(),
                            entryContent.getIsSeasonFinale(), entryContent.getIsSeriesFinale(), null, null),
                    null, null, entry.getWatchedDate(), entry.getWatchNumber(), null, null,
                    entry.getIgnore(), entry.getCreatedAt(), entry.getUpdatedAt(), 0, false);
        });

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId,
                new DiaryEntryBulkCreationDTO(new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null),
                        LocalDate.of(2026, 9, 21), null, null, null));

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(entry -> entry.content().type() == ContentType.EPISODE);
        assertThat(result).allMatch(DiaryEntryResponseDTO::ignore);
        verify(contentService, times(2)).getOrCreateReference(contentRefCreationCaptor.capture(), eq(true));
        assertThat(contentRefCreationCaptor.getAllValues()).filteredOn(ref -> ref.episodeNumber() == 2)
                .allSatisfy(ref -> {
                    assertThat(ref.isSeasonFinale()).isNull();
                    assertThat(ref.isSeriesFinale()).isNull();
                });
        assertThat(globalFinale.getIsSeasonFinale()).isTrue();
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Throw BadRequestException - When The Season Exceeds The Bulk Episode Limit And TMDB Cannot Verify It")
    void shouldThrowBadRequestExceptionWhenTheSeasonExceedsTheBulkEpisodeLimit() {
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(
                seasonRef, LocalDate.now(), DiaryEntryServiceImpl.MAX_BULK_EPISODES + 1, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("exceeding the bulk log limit")
                .hasMessageContaining("could not be verified against TMDB");
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Allow The Season To Exceed The Bulk Episode Limit - When TMDB Confirms The Real Episode Count")
    void shouldAllowTheSeasonToExceedTheBulkEpisodeLimitWhenTmdbConfirmsTheRealEpisodeCount() {
        int totalEpisodes = DiaryEntryServiceImpl.MAX_BULK_EPISODES + 1;
        List<TmdbEpisodeSummary> episodes = new ArrayList<>();
        for (int episodeNumber = 1; episodeNumber <= totalEpisodes; episodeNumber++) {
            episodes.add(new TmdbEpisodeSummary(episodeNumber, null, null, "2020-01-01", 45, null, null));
        }

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSeasonFullDetails(null, null, null, null, null, null, episodes, null, null)));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenAnswer(inv -> {
                    ContentRefCreationDTO refDto = inv.getArgument(0);
                    return new ContentRefDTO(UUID.randomUUID(), null, ContentType.EPISODE, "900", 1,
                            refDto.episodeNumber(), null, null, LocalDateTime.now(), LocalDateTime.now());
                });
        when(contentRepository.getReferenceById(any(UUID.class)))
                .thenAnswer(inv -> buildEpisode("900", 1, null));
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(inv -> buildResponseDto(inv.getArgument(0)));

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.now(), totalEpisodes, null, null);

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        assertThat(result).hasSize(totalEpisodes);
        verify(contentService, times(totalEpisodes)).getOrCreateReference(any(ContentRefCreationDTO.class), eq(true));
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Throw BadRequestException - When The Series Exceeds The Bulk Episode Limit And TMDB Cannot Verify It")
    void shouldThrowBadRequestExceptionWhenTheSeriesExceedsTheBulkEpisodeLimit() {
        int totalEpisodes = DiaryEntryServiceImpl.MAX_BULK_EPISODES + 1;
        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(
                seriesRef, LocalDate.now(), null, 1, Map.of(1, totalEpisodes));

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("exceeding the bulk log limit")
                .hasMessageContaining("could not be verified against TMDB");
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Allow The Series To Exceed The Bulk Episode Limit - When TMDB Confirms The Real Episode Count")
    void shouldAllowTheSeriesToExceedTheBulkEpisodeLimitWhenTmdbConfirmsTheRealEpisodeCount() {
        int totalEpisodes = DiaryEntryServiceImpl.MAX_BULK_EPISODES + 1;
        when(tmdbClient.getTvFullDetails("900", lucas.getPreferredLanguage())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbTvFullDetails(null, null, null, null, null, null, null, null, null, null, null,
                        List.of(), null, null, null, null, null, totalEpisodes, null, null, null)));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenReturn(new ContentRefDTO(UUID.randomUUID(), "900", ContentType.SERIES, null, null, null, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenAnswer(inv -> {
                    ContentRefCreationDTO refDto = inv.getArgument(0);
                    return new ContentRefDTO(UUID.randomUUID(), null, ContentType.EPISODE, "900", 1,
                            refDto.episodeNumber(), null, null, LocalDateTime.now(), LocalDateTime.now());
                });
        when(contentRepository.getReferenceById(any(UUID.class)))
                .thenAnswer(inv -> buildEpisode("900", 1, null));
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(inv -> buildResponseDto(inv.getArgument(0)));

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(
                seriesRef, LocalDate.now(), null, 1, Map.of(1, totalEpisodes));

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        assertThat(result).hasSize(totalEpisodes);
        verify(contentService, times(totalEpisodes)).getOrCreateReference(any(ContentRefCreationDTO.class), eq(true));
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Throw BadRequestException - When A SERIES Bulk Request Requires Explicit FinaleSeasonNumber")
    void shouldThrowBadRequestExceptionWhenSeriesBulkRequestRequiresFinaleSeasonNumber() {
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("900", ContentType.SEASON))
                .thenReturn(Optional.empty());

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seriesRef, LocalDate.now(), null, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("finaleSeasonNumber is required");
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Throw BadRequestException - When A SERIES Bulk Request Has A Season Missing Its Finale Episode")
    void shouldThrowBadRequestExceptionWhenASeriesBulkRequestHasASeasonMissingItsFinaleEpisode() {
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("900", ContentType.SEASON))
                .thenReturn(Optional.of(buildFinaleSeason("900", 2)));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(buildFinaleEpisode("900", 1, 3)));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 2, ContentType.EPISODE))
                .thenReturn(Optional.empty());

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seriesRef, LocalDate.now(), null, 2, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("finaleEpisodeNumber is required");
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Throw BadRequestException - When SeasonFinaleEpisodeNumbers Supplies A Value Below 1")
    void shouldThrowBadRequestExceptionWhenSeasonFinaleEpisodeNumbersSuppliesAValueBelowOne() {
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("900", ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seriesRef, LocalDate.now(), null, 1, Map.of(1, 0));

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must be greater than or equal to 1");
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Bulk-Log Every Season From Scratch - When SeasonFinaleEpisodeNumbers Supplies Every Season's Finale")
    void shouldBulkLogEverySeasonFromScratchWhenSeasonFinaleEpisodeNumbersSuppliesEveryFinale() {
        Content s1e1 = buildEpisode("900", 1, 1);
        Content s1e2 = buildFinaleEpisode("900", 1, 2);
        Content s2e1 = buildEpisode("900", 2, 1);
        Content s2e2 = buildFinaleEpisode("900", 2, 2);

        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("900", ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 2, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);

        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenReturn(new ContentRefDTO(UUID.randomUUID(), "900", ContentType.SERIES, null, null, null, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenAnswer(inv -> {
                    ContentRefCreationDTO refDto = inv.getArgument(0);
                    Content content;
                    if (refDto.episodeNumber() == 1 && refDto.seasonNumber() == 1) content = s1e1;
                    else if (refDto.episodeNumber() == 2 && refDto.seasonNumber() == 1) content = s1e2;
                    else if (refDto.episodeNumber() == 1 && refDto.seasonNumber() == 2) content = s2e1;
                    else content = s2e2;
                    return new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(), null, null, null,
                            null, null, LocalDateTime.now(), LocalDateTime.now());
                });

        when(contentRepository.getReferenceById(any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID id = inv.getArgument(0);
                    if (id.equals(s1e1.getId())) return s1e1;
                    if (id.equals(s1e2.getId())) return s1e2;
                    if (id.equals(s2e1.getId())) return s2e1;
                    if (id.equals(s2e2.getId())) return s2e2;
                    return null;
                });

        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(inv -> buildResponseDto(inv.getArgument(0)));

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(
                seriesRef, LocalDate.now(), null, 2, Map.of(1, 2, 2, 2));

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        assertThat(result).hasSize(4);
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Successfully Process All Seasons - When Bulk-Logging A Complete SERIES")
    void shouldSuccessfullyProcessAllSeasonsWhenBulkLoggingACompleteSeries() {
        // Setup: Series with 2 seasons (S1: 2 episodes, S2: 2 episodes)
        Content s1e1 = buildFinaleEpisode("900", 1, 2);
        Content s1e2 = buildEpisode("900", 1, 1);
        Content s2e1 = buildFinaleEpisode("900", 2, 2);
        Content s2e2 = buildEpisode("900", 2, 1);

        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("900", ContentType.SEASON))
                .thenReturn(Optional.of(buildFinaleSeason("900", 2)));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(s1e1));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 2, ContentType.EPISODE))
                .thenReturn(Optional.of(s2e1));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);

        // Mock contentService and contentRepository to work together
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenReturn(new ContentRefDTO(UUID.randomUUID(), "900", ContentType.SERIES, null, null, null, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenAnswer(inv -> {
                    ContentRefCreationDTO dto = inv.getArgument(0);
                    Content content;
                    if (dto.episodeNumber() == 1 && dto.seasonNumber() == 1) content = s1e1;
                    else if (dto.episodeNumber() == 2 && dto.seasonNumber() == 1) content = s1e2;
                    else if (dto.episodeNumber() == 1 && dto.seasonNumber() == 2) content = s2e1;
                    else content = s2e2;
                    return new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(), null, null, null,
                            null, null, LocalDateTime.now(), LocalDateTime.now());
                });

        when(contentRepository.getReferenceById(any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID id = inv.getArgument(0);
                    if (id.equals(s1e1.getId())) return s1e1;
                    if (id.equals(s1e2.getId())) return s1e2;
                    if (id.equals(s2e1.getId())) return s2e1;
                    if (id.equals(s2e2.getId())) return s2e2;
                    return null;
                });

        // Mock repository save
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Mock mapper to return DTOs
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    DiaryEntry entry = inv.getArgument(0);
                    return buildResponseDto(entry);
                });

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seriesRef, LocalDate.now(), null, 2, null);

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        // Should return 4 episodes (2 per season, not the season entries)
        assertThat(result).hasSize(4);
        assertThat(result).allMatch(entry -> entry.watchNumber() == 1);
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Resume Only T2 Pass 2 - When T1 Pass 2 And T2 Pass 1 Are Complete")
    void shouldResumeOnlyT2PassTwoWhenT1PassTwoAndT2PassOneAreComplete() {
        Content s1e1 = buildFinaleEpisode("900", 1, 1);
        Content s2e1 = buildFinaleEpisode("900", 2, 1);
        Content s1 = buildSeason("900", 1);
        Content s2 = buildFinaleSeason("900", 2);
        Content series = buildContent("900", ContentType.SERIES);

        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("900", ContentType.SEASON))
                .thenReturn(Optional.of(s2));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 2, ContentType.EPISODE))
                .thenReturn(Optional.of(s2e1));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(s1e1));
        when(diaryEntryRepository.findEpisodeEntriesBySeriesForUser(lucasId, "900"))
                .thenReturn(List.of(
                        buildDiaryEntry(lucas, s1e1, 1),
                        buildDiaryEntry(lucas, s1e1, 2),
                        buildDiaryEntry(lucas, s2e1, 1)));
        when(diaryEntryRepository.findEpisodeEntriesByUserIdAndSeriesTmdbIdAndSeasonNumber(lucasId, "900", 1))
                .thenReturn(List.of(buildDiaryEntry(lucas, s1e1, 1), buildDiaryEntry(lucas, s1e1, 2)));
        when(diaryEntryRepository.findEpisodeEntriesByUserIdAndSeriesTmdbIdAndSeasonNumber(lucasId, "900", 2))
                .thenReturn(List.of(buildDiaryEntry(lucas, s2e1, 1), buildDiaryEntry(lucas, s2e1, 2)));
        when(diaryEntryRepository.findFirstByUserIdAndContentIdAndWatchNumber(lucasId, s1.getId(), 2)).thenReturn(Optional.empty());
        when(diaryEntryRepository.findFirstByUserIdAndContentIdAndWatchNumber(lucasId, s2.getId(), 2)).thenReturn(Optional.empty());
        when(diaryEntryRepository.findFirstByUserIdAndContentIdAndWatchNumber(lucasId, series.getId(), 2))
                .thenReturn(Optional.empty());
        when(watchCompanionRepository.findByDiaryEntryIdIn(any())).thenReturn(List.of());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);

        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenAnswer(invocation -> {
                    ContentRefCreationDTO ref = invocation.getArgument(0);
                    Content content = switch (ref.type()) {
                        case SERIES -> series;
                        case SEASON -> ref.seasonNumber() == 1 ? s1 : s2;
                        default -> throw new AssertionError("Unexpected content type: " + ref.type());
                    };
                    return new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(),
                            content.getSeriesTmdbId(), content.getSeasonNumber(), content.getEpisodeNumber(),
                            content.getIsSeasonFinale(), content.getIsSeriesFinale(), null, null);
                });
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenAnswer(invocation -> {
                    ContentRefCreationDTO ref = invocation.getArgument(0);
                    Content content = switch (ref.seasonNumber()) {
                        case 1 -> s1e1;
                        case 2 -> s2e1;
                        default -> throw new AssertionError("Unexpected season: " + ref.seasonNumber());
                    };
                    return new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(),
                            content.getSeriesTmdbId(), content.getSeasonNumber(), content.getEpisodeNumber(),
                            content.getIsSeasonFinale(), content.getIsSeriesFinale(), null, null);
                });
        when(contentRepository.getReferenceById(any(UUID.class)))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(0);
                    return List.of(s1e1, s2e1, s1, s2, series).stream()
                            .filter(content -> content.getId().equals(id))
                            .findFirst()
                            .orElseThrow();
                });
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(invocation -> buildResponseDtoWithContent(invocation.getArgument(0)));

        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(
                new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null),
                LocalDate.of(2024, 5, 1), null, 2, Map.of(1, 1, 2, 1));

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().content().type()).isEqualTo(ContentType.EPISODE);
        assertThat(result.getFirst().content().seasonNumber()).isEqualTo(2);
        assertThat(result.getFirst().content().episodeNumber()).isEqualTo(1);
        assertThat(result.getFirst().watchNumber()).isEqualTo(2);
        verify(diaryEntryRepository).saveAndFlush(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getContent().getType()).isEqualTo(ContentType.EPISODE);
        assertThat(entryCaptor.getValue().getContent().getSeasonNumber()).isEqualTo(2);
        assertThat(entryCaptor.getValue().getContent().getEpisodeNumber()).isEqualTo(1);
        assertThat(entryCaptor.getValue().getWatchNumber()).isEqualTo(2);
    }

    // ---------- createDiaryEntriesInBulk: watchlist/dropped removal side effect ----------

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Remove The Series Watchlist And Dropped Entries - When Bulk-Logging A Season")
    void shouldRemoveTheSeriesWatchlistAndDroppedEntriesWhenBulkLoggingASeason() {
        Content e1 = buildFinaleEpisode("900", 1, 1);
        Content seriesContent = buildContent("900", ContentType.SERIES);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenReturn(new ContentRefDTO(e1.getId(), e1.getTmdbId(), ContentType.EPISODE, "900", 1, 1, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentRepository.getReferenceById(e1.getId())).thenReturn(e1);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));
        when(contentRepository.findByTmdbIdAndType("900", ContentType.SERIES)).thenReturn(Optional.of(seriesContent));

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.now(), 1, null, null);

        diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        verify(diaryWatchCommandService, atLeastOnce()).recordPrepared(eq(lucasId), any(WatchEventCommand.class));
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Remove The Series Watchlist And Dropped Entries - When Bulk-Logging A Series")
    void shouldRemoveTheSeriesWatchlistAndDroppedEntriesWhenBulkLoggingASeries() {
        Content e1 = buildFinaleEpisode("900", 1, 1);
        Content seriesContent = buildContent("900", ContentType.SERIES);

        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("900", ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenReturn(new ContentRefDTO(seriesContent.getId(), "900", ContentType.SERIES, null, null, null, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenReturn(new ContentRefDTO(e1.getId(), e1.getTmdbId(), ContentType.EPISODE, "900", 1, 1, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentRepository.getReferenceById(e1.getId())).thenReturn(e1);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));
        when(contentRepository.findByTmdbIdAndType("900", ContentType.SERIES)).thenReturn(Optional.of(seriesContent));

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seriesRef, LocalDate.now(), null, 1, Map.of(1, 1));

        diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        verify(diaryWatchCommandService, atLeastOnce()).recordPrepared(eq(lucasId), any(WatchEventCommand.class));
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Derive RuntimeMinutes From TMDB Season Details - When Bulk-Logging A Season")
    void shouldDeriveRuntimeMinutesFromTmdbSeasonDetailsWhenBulkLoggingASeason() {
        Content e1 = buildEpisode("900", 1, 1);
        Content e2 = buildFinaleEpisode("900", 1, 2);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.Found<>(seasonDetailsWithRuntimes(Map.of(1, 47, 2, 55))));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenAnswer(inv -> {
                    ContentRefCreationDTO refDto = inv.getArgument(0);
                    Content content = refDto.episodeNumber() == 1 ? e1 : e2;
                    return new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(), content.getSeriesTmdbId(),
                            content.getSeasonNumber(), content.getEpisodeNumber(), content.getIsSeasonFinale(),
                            content.getIsSeriesFinale(), null, null);
                });
        when(contentRepository.getReferenceById(any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID id = inv.getArgument(0);
                    return id.equals(e1.getId()) ? e1 : e2;
                });
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.now(), 2, null, null);

        diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        verify(contentService, times(2)).getOrCreateReference(contentRefCreationCaptor.capture(), eq(true));
        Map<Integer, Integer> runtimeByEpisode = contentRefCreationCaptor.getAllValues().stream()
                .collect(Collectors.toMap(ContentRefCreationDTO::episodeNumber, ContentRefCreationDTO::runtimeMinutes));
        assertThat(runtimeByEpisode).containsEntry(1, 47).containsEntry(2, 55);
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Omit RuntimeMinutes For An Episode Missing From TMDB Season Details - When Bulk-Logging A Season")
    void shouldOmitRuntimeMinutesForAnEpisodeMissingFromTmdbSeasonDetailsWhenBulkLoggingASeason() {
        Content e1 = buildFinaleEpisode("900", 1, 1);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.Found<>(seasonDetailsWithRuntimes(Map.of())));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenReturn(new ContentRefDTO(e1.getId(), e1.getTmdbId(), ContentType.EPISODE, "900", 1, 1, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentRepository.getReferenceById(e1.getId())).thenReturn(e1);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.now(), 1, null, null);

        diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        verify(contentService).getOrCreateReference(contentRefCreationCaptor.capture(), eq(true));
        assertThat(contentRefCreationCaptor.getValue().runtimeMinutes()).isNull();
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Fetch Each Season's Episode RuntimeMinutes From TMDB Once Per Season - When Bulk-Logging A Series")
    void shouldFetchEachSeasonsEpisodeRuntimeMinutesFromTmdbOncePerSeasonWhenBulkLoggingASeries() {
        Content e1 = buildFinaleEpisode("900", 1, 1);

        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("900", ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.Found<>(seasonDetailsWithRuntimes(Map.of(1, 47))));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenReturn(new ContentRefDTO(UUID.randomUUID(), "900", ContentType.SERIES, null, null, null, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenReturn(new ContentRefDTO(e1.getId(), e1.getTmdbId(), ContentType.EPISODE, "900", 1, 1, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentRepository.getReferenceById(e1.getId())).thenReturn(e1);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seriesRef, LocalDate.now(), null, 1, Map.of(1, 1));

        diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        verify(contentService).getOrCreateReference(contentRefCreationCaptor.capture(), eq(true));
        assertThat(contentRefCreationCaptor.getValue().runtimeMinutes()).isEqualTo(47);
        verify(tmdbClient, times(1)).getSeasonFullDetails("900", 1, lucas.getPreferredLanguage());
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Throw TmdbUnavailableException - When TMDB Fails To Return A Season's Episode Runtimes")
    void shouldThrowTmdbUnavailableExceptionWhenTmdbFailsToReturnASeasonsEpisodeRuntimes() {
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage())).thenReturn(new TmdbLookupResult.Unavailable<>());

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.now(), 2, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dto))
                .isInstanceOf(TmdbUnavailableException.class);

        verify(contentService, never()).getOrCreateReference(any());
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Log Only Episodes Released By WatchedDate - On Season Bulk")
    void shouldLogOnlyEpisodesReleasedByWatchedDateOnSeasonBulk() {
        Content e1 = buildEpisode("900", 1, 1);
        Content e2 = buildEpisode("900", 1, 2);
        Content e3 = buildEpisode("900", 1, 3);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSeasonFullDetails(null, null, null, null, null, null, List.of(
                        new TmdbEpisodeSummary(1, null, null, "2026-09-20", 45, null, null),
                        new TmdbEpisodeSummary(2, null, null, "2026-09-21", 45, null, null),
                        new TmdbEpisodeSummary(3, null, null, "2026-09-22", 45, null, null)),
                        null, null)));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenAnswer(inv -> {
                    ContentRefCreationDTO refDto = inv.getArgument(0);
                    Content content = switch (refDto.episodeNumber()) {
                        case 1 -> e1;
                        case 2 -> e2;
                        default -> e3;
                    };
                    return new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(), null, null, null,
                            null, null, LocalDateTime.now(), LocalDateTime.now());
                });
        when(contentRepository.getReferenceById(any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID id = inv.getArgument(0);
                    if (id.equals(e1.getId())) return e1;
                    if (id.equals(e2.getId())) return e2;
                    return e3;
                });
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.of(2026, 9, 21), null, null, null);

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        assertThat(result).hasSize(2);
        verify(contentService, times(2)).getOrCreateReference(contentRefCreationCaptor.capture(), eq(true));
        assertThat(contentRefCreationCaptor.getAllValues())
                .extracting(ContentRefCreationDTO::episodeNumber)
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Derive FinaleSeasonNumber From TMDB Aired Seasons - When Omitted And No Existing Finale Content")
    void shouldDeriveFinaleSeasonNumberFromTmdbAiredSeasonsWhenOmittedAndNoExistingFinaleContent() {
        Content e1 = buildEpisode("900", 1, 1);
        Content e2 = buildEpisode("900", 2, 1);

        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("900", ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(eq("900"), any(), eq(ContentType.EPISODE)))
                .thenReturn(Optional.empty());
        when(tmdbClient.getTvFullDetails("900", lucas.getPreferredLanguage())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbTvFullDetails(null, null, null, null, null, null, null, null, null, null, null, List.of(
                        new TmdbSeasonSummary(1, null, null, "2020-01-01", 1, null),
                        new TmdbSeasonSummary(2, null, null, "2021-01-01", 1, null),
                        new TmdbSeasonSummary(3, null, null, "2099-01-01", 1, null)),
                        null, null, null, null, null, null, null, null, null)));
        when(tmdbClient.getSeasonFullDetails(eq("900"), any(), eq(lucas.getPreferredLanguage()))).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSeasonFullDetails(null, null, null, null, null, null, List.of(
                        new TmdbEpisodeSummary(1, null, null, "2020-01-01", 45, null, null)),
                        null, null)));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenReturn(new ContentRefDTO(UUID.randomUUID(), "900", ContentType.SERIES, null, null, null, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenAnswer(inv -> {
                    ContentRefCreationDTO refDto = inv.getArgument(0);
                    Content content = refDto.seasonNumber() == 1 ? e1 : e2;
                    return new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(), null, null, null,
                            null, null, LocalDateTime.now(), LocalDateTime.now());
                });
        when(contentRepository.getReferenceById(any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID id = inv.getArgument(0);
                    return id.equals(e1.getId()) ? e1 : e2;
                });
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seriesRef, LocalDate.now(), null, null, null);

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        assertThat(result).hasSize(2);
        verify(tmdbClient, never()).getSeasonFullDetails(eq("900"), eq(3), any());
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Ignore Client FinaleEpisodeNumber - When TMDB Already Knows The Season's Aired Episode Count")
    void shouldIgnoreClientFinaleEpisodeNumberWhenTmdbAlreadyKnowsTheSeasonsAiredEpisodeCount() {
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSeasonFullDetails(null, null, null, null, null, null, List.of(
                        new TmdbEpisodeSummary(1, null, null, "2020-01-01", 45, null, null),
                        new TmdbEpisodeSummary(2, null, null, "2020-01-08", 45, null, null),
                        new TmdbEpisodeSummary(3, null, null, "2020-01-15", 45, null, null)),
                        null, null)));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenAnswer(inv -> {
                    ContentRefCreationDTO refDto = inv.getArgument(0);
                    return new ContentRefDTO(UUID.randomUUID(), null, ContentType.EPISODE, "900", 1,
                            refDto.episodeNumber(), null, null, LocalDateTime.now(), LocalDateTime.now());
                });
        when(contentRepository.getReferenceById(any(UUID.class)))
                .thenAnswer(inv -> buildEpisode("900", 1, null));
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(inv -> buildResponseDto(inv.getArgument(0)));

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.now(), 1, null, null);

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        assertThat(result).hasSize(3);
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Throw BadRequestException - When Explicit FinaleEpisodeNumber Exceeds The Season's Real Episode Count Known By TMDB")
    void shouldThrowBadRequestExceptionWhenExplicitFinaleEpisodeNumberExceedsTheSeasonsRealEpisodeCountKnownByTmdb() {
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.Found<>(seasonDetailsWithRuntimes(Map.of(1, 45, 2, 45, 3, 45))));

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.now(), 10, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("exceeds the 3 episodes known by TMDB");
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Ignore Client FinaleSeasonNumber - When TMDB Already Knows The Series' Latest Aired Season")
    void shouldIgnoreClientFinaleSeasonNumberWhenTmdbAlreadyKnowsTheSeriesLatestAiredSeason() {
        Content e1 = buildEpisode("900", 1, 1);
        Content e2 = buildEpisode("900", 2, 1);

        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("900", ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(eq("900"), any(), eq(ContentType.EPISODE)))
                .thenReturn(Optional.empty());
        when(tmdbClient.getTvFullDetails("900", lucas.getPreferredLanguage())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbTvFullDetails(null, null, null, null, null, null, null, null, null, null, null, List.of(
                        new TmdbSeasonSummary(1, null, null, "2020-01-01", 1, null),
                        new TmdbSeasonSummary(2, null, null, "2021-01-01", 1, null)),
                        null, null, null, null, null, null, null, null, null)));
        when(tmdbClient.getSeasonFullDetails(eq("900"), any(), eq(lucas.getPreferredLanguage()))).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSeasonFullDetails(null, null, null, null, null, null, List.of(
                        new TmdbEpisodeSummary(1, null, null, "2020-01-01", 45, null, null)),
                        null, null)));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenReturn(new ContentRefDTO(UUID.randomUUID(), "900", ContentType.SERIES, null, null, null, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenAnswer(inv -> {
                    ContentRefCreationDTO refDto = inv.getArgument(0);
                    Content content = refDto.seasonNumber() == 1 ? e1 : e2;
                    return new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(), null, null, null,
                            null, null, LocalDateTime.now(), LocalDateTime.now());
                });
        when(contentRepository.getReferenceById(any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID id = inv.getArgument(0);
                    return id.equals(e1.getId()) ? e1 : e2;
                });
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seriesRef, LocalDate.now(), null, 1, null);

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Throw BadRequestException - When Explicit FinaleSeasonNumber Exceeds The Series' Real Season Count Known By TMDB")
    void shouldThrowBadRequestExceptionWhenExplicitFinaleSeasonNumberExceedsTheSeriesRealSeasonCountKnownByTmdb() {
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("900", ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(tmdbClient.getTvFullDetails("900", lucas.getPreferredLanguage())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbTvFullDetails(null, null, null, null, null, null, null, null, null, null, null, List.of(
                        new TmdbSeasonSummary(1, null, null, null, 1, null),
                        new TmdbSeasonSummary(2, null, null, null, 1, null)),
                        null, null, null, null, null, null, null, null, null)));

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seriesRef, LocalDate.now(), null, 5, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("exceeds the 2 seasons known by TMDB");
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Reject A SERIES Bulk - When Existing Finale Season Date Is Unknown And Other Seasons Are Future")
    void shouldRejectSeriesBulkWhenExistingFinaleSeasonDateIsUnknownAndOtherSeasonsAreFuture() {
        Content existingFinaleSeason = buildFinaleSeason("900", 1);

        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("900", ContentType.SEASON))
                .thenReturn(Optional.of(existingFinaleSeason));
        when(tmdbClient.getTvFullDetails("900", lucas.getPreferredLanguage())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbTvFullDetails(null, null, null, null, null, null, null, null, null, null, null,
                        List.of(
                                new TmdbSeasonSummary(1, null, null, "not-a-date", 1, null),
                                new TmdbSeasonSummary(2, null, null, "2026-09-22", 1, null)),
                        null, null, null, null, null, null, null, null, null, null)));

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(
                seriesRef, LocalDate.of(2026, 9, 21), null, null, Map.of(1, 1));

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dto))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("No seasons in the series have been released by 2026-09-21");

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Throw TmdbUnavailableException - When A SERIES Bulk Request Has No Existing Finale, No Explicit FinaleSeasonNumber, And TMDB Is Unavailable")
    void shouldThrowTmdbUnavailableExceptionWhenSeriesBulkRequestHasNoExistingFinaleNoExplicitFinaleSeasonNumberAndTmdbIsUnavailable() {
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("900", ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(tmdbClient.getTvFullDetails("900", lucas.getPreferredLanguage())).thenReturn(new TmdbLookupResult.Unavailable<>());

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seriesRef, LocalDate.now(), null, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntriesInBulk(lucasId, dto))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Resume The Incomplete First Pass On A Season - When Episode 1 Has A Watch Record And Episodes 2 And 3 Are Missing")
    void shouldResumeTheIncompleteFirstPassOnSeasonBulk() {
        Content e1 = buildFinaleEpisode("900", 1, 3);
        Content e2 = buildEpisode("900", 1, 2);
        Content e3 = buildEpisode("900", 1, 1);
        DiaryEntry existingEpisodeEntry = buildDiaryEntry(lucas, e1, 1);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(e1));
        when(diaryEntryRepository.findEpisodeEntriesByUserIdAndSeriesTmdbIdAndSeasonNumber(lucasId, "900", 1))
                .thenReturn(List.of(existingEpisodeEntry));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);

        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenAnswer(inv -> {
                    ContentRefCreationDTO dto = inv.getArgument(0);
                    Content content;
                    if (dto.episodeNumber() == 3) content = e1;
                    else if (dto.episodeNumber() == 2) content = e2;
                    else content = e3;
                    return new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(), null, null, null,
                             null, null, LocalDateTime.now(), LocalDateTime.now());
                 });

        when(contentRepository.getReferenceById(any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID id = inv.getArgument(0);
                    if (id.equals(e1.getId())) return e1;
                    if (id.equals(e2.getId())) return e2;
                    if (id.equals(e3.getId())) return e3;
                     return null;
                 });

        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    DiaryEntry entry = inv.getArgument(0);
                    return buildResponseDto(entry);
                });

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.now(), 3, null, null);

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        assertThat(result).hasSize(2);
        verify(diaryEntryRepository, times(2)).saveAndFlush(entryCaptor.capture());
        assertThat(entryCaptor.getAllValues())
                .extracting(entry -> entry.getContent().getEpisodeNumber(), DiaryEntry::getWatchNumber)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1, 1),
                        org.assertj.core.groups.Tuple.tuple(2, 1));
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Resume The Incomplete Rewatch Pass On A Season - When Only Episode 1 Has A Second Watch")
    void shouldResumeTheIncompleteRewatchPassOnSeasonBulk() {
        Content e1 = buildFinaleEpisode("900", 1, 3);
        Content e2 = buildEpisode("900", 1, 2);
        Content e3 = buildEpisode("900", 1, 1);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(e1));
        when(diaryEntryRepository.findEpisodeEntriesByUserIdAndSeriesTmdbIdAndSeasonNumber(lucasId, "900", 1))
                .thenReturn(List.of(
                        buildDiaryEntry(lucas, e1, 1),
                        buildDiaryEntry(lucas, e1, 2),
                        buildDiaryEntry(lucas, e2, 1),
                        buildDiaryEntry(lucas, e3, 1)));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenAnswer(inv -> {
                    ContentRefCreationDTO refDto = inv.getArgument(0);
                    Content content = refDto.episodeNumber() == 3 ? e1 : refDto.episodeNumber() == 2 ? e2 : e3;
                    return new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(), null, null, null,
                            null, null, LocalDateTime.now(), LocalDateTime.now());
                });
        when(contentRepository.getReferenceById(any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID id = inv.getArgument(0);
                    if (id.equals(e1.getId())) return e1;
                    if (id.equals(e2.getId())) return e2;
                    if (id.equals(e3.getId())) return e3;
                    return null;
                });
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(inv -> buildResponseDto(inv.getArgument(0)));

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.now(), 3, null, null);

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        assertThat(result).hasSize(2);
        verify(diaryEntryRepository, times(2)).saveAndFlush(entryCaptor.capture());
        assertThat(entryCaptor.getAllValues())
                .extracting(entry -> entry.getContent().getEpisodeNumber(), DiaryEntry::getWatchNumber)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1, 2),
                        org.assertj.core.groups.Tuple.tuple(2, 2));
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Fill The Lowest Missing Pass On A Season - When A Nonlatest Pass Was Deleted")
    void shouldFillTheLowestMissingPassOnSeasonAfterNonlatestPassDeletion() {
        Content e1 = buildFinaleEpisode("900", 1, 2);
        Content e2 = buildEpisode("900", 1, 1);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(e1));
        when(diaryEntryRepository.findEpisodeEntriesByUserIdAndSeriesTmdbIdAndSeasonNumber(lucasId, "900", 1))
                .thenReturn(List.of(buildDiaryEntry(lucas, e1, 2), buildDiaryEntry(lucas, e2, 1), buildDiaryEntry(lucas, e2, 2)));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true))).thenAnswer(inv -> {
            ContentRefCreationDTO ref = inv.getArgument(0);
            Content content = ref.episodeNumber() == 2 ? e1 : e2;
            return new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(), null, null, null, null, null, LocalDateTime.now(), LocalDateTime.now());
        });
        when(contentRepository.getReferenceById(any(UUID.class))).thenAnswer(inv -> inv.getArgument(0).equals(e1.getId()) ? e1 : e2);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenAnswer(inv -> buildResponseDto(inv.getArgument(0)));

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId,
                new DiaryEntryBulkCreationDTO(new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null), LocalDate.now(), 2, null, null));

        assertThat(result).hasSize(1);
        verify(diaryEntryRepository).saveAndFlush(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getContent().getEpisodeNumber()).isEqualTo(2);
        assertThat(entryCaptor.getValue().getWatchNumber()).isEqualTo(1);
    }

    // ---------- updateDiaryEntry ----------

    @Test
    @DisplayName("[updateDiaryEntry] Should Throw NotFoundException - When Entry Does Not Exist")
    void shouldThrowNotFoundExceptionWhenEntryDoesNotExistOnUpdate() {
        UUID missingId = UUID.randomUUID();
        when(diaryEntryRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> diaryEntryService.updateDiaryEntry(lucasId, missingId, minimalUpdateDto()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Diary entry not found");

        verify(diaryEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Throw NotFoundException - When Entry Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenEntryBelongsToADifferentUser() {
        DiaryEntry marinasEntry = buildEntry(marina, fightClub);
        when(diaryEntryRepository.findById(marinasEntry.getId())).thenReturn(Optional.of(marinasEntry));

        assertThatThrownBy(() -> diaryEntryService.updateDiaryEntry(lucasId, marinasEntry.getId(), minimalUpdateDto()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Diary entry not found");

        verify(diaryEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Not Touch Content - When Called")
    void shouldNotTouchContentWhenCalled() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(entry);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false, List.of())).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), minimalUpdateDto());

        verifyNoInteractions(contentService, contentRepository);
        verify(diaryEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getContent()).isEqualTo(fightClub);
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Not Change Optional Fields - When All Are Null")
    void shouldNotChangeOptionalFieldsWhenAllAreNull() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        entry.setComment("Original comment");
        entry.setScore(7);
        entry.setWatchedDate(LocalDate.of(2023, 1, 1));
        entry.setWatchNumber(2);
        entry.setWatchedInTheater(true);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(entry);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false, List.of())).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), minimalUpdateDto());

        verify(diaryEntryRepository).save(entryCaptor.capture());
        DiaryEntry saved = entryCaptor.getValue();
        assertThat(saved.getComment()).isEqualTo("Original comment");
        assertThat(saved.getScore()).isEqualTo(7);
        assertThat(saved.getWatchedDate()).isEqualTo(LocalDate.of(2023, 1, 1));
        assertThat(saved.getWatchNumber()).isEqualTo(2);
        assertThat(saved.getWatchedInTheater()).isTrue();
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Set Ignore To False - When Editing An Ignored Bulk-Child Entry")
    void shouldSetIgnoreToFalseWhenEditingAnIgnoredBulkChildEntry() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        entry.setIgnore(true);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), minimalUpdateDto());

        assertThat(entry.getIgnore()).isFalse();
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Leave Companions Untouched - When WatchedWith Is Not Provided")
    void shouldLeaveCompanionsUntouchedWhenWatchedWithIsNotProvided() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), minimalUpdateDto());

        verify(watchCompanionRepository, never()).deleteByDiaryEntryId(any());
        verify(watchCompanionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Replace Companions - When WatchedWith Is Provided")
    void shouldReplaceCompanionsWhenWatchedWithIsProvided() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(lucasId, marinaId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(userRepository.getReferenceById(marinaId)).thenReturn(marina);
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(),
                new DiaryEntryUpdateDTO(null, null, null, null, null, List.of(marinaId)));

        InOrder order = inOrder(watchCompanionRepository);
        order.verify(watchCompanionRepository).deleteByDiaryEntryId(entry.getId());
        order.verify(watchCompanionRepository).saveAll(watchCompanionsCaptor.capture());
        assertThat(watchCompanionsCaptor.getValue()).hasSize(1);
        assertThat(watchCompanionsCaptor.getValue().get(0).getUser()).isEqualTo(marina);
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Clear Companions - When WatchedWith Is An Empty List")
    void shouldClearCompanionsWhenWatchedWithIsAnEmptyList() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(),
                new DiaryEntryUpdateDTO(null, null, null, null, null, List.of()));

        verify(watchCompanionRepository).deleteByDiaryEntryId(entry.getId());
        verify(watchCompanionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Update Comment - When A Different Value Is Provided")
    void shouldUpdateCommentWhenADifferentValueIsProvided() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        entry.setComment("Old comment");
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(entry);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false, List.of())).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), new DiaryEntryUpdateDTO(
                "New comment", null, null, null, null));

        verify(diaryEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getComment()).isEqualTo("New comment");
        verify(userContentMutationLock).lock(lucasId);
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Not Resurrect Entry - When It Was Deleted Before The Mutation Lock Was Acquired")
    void shouldNotResurrectEntryWhenDeletedBeforeMutationLockWasAcquired() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(diaryEntryRepository.existsById(entry.getId())).thenReturn(false);

        assertThatThrownBy(() -> diaryEntryService.updateDiaryEntry(lucasId, entry.getId(),
                new DiaryEntryUpdateDTO("Updated review", null, null, null, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Diary entry not found");

        verify(userContentMutationLock).lock(lucasId);
        verify(diaryEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Update Score - When A Different Value Is Provided")
    void shouldUpdateScoreWhenADifferentValueIsProvided() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        entry.setScore(5);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(entry);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false, List.of())).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), new DiaryEntryUpdateDTO(
                null, 10, null, null, null));

        verify(diaryEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getScore()).isEqualTo(10);
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Update WatchedDate - When A Different Value Is Provided")
    void shouldUpdateWatchedDateWhenADifferentValueIsProvided() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        entry.setWatchedDate(LocalDate.of(2023, 1, 1));
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(entry);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false, List.of())).thenReturn(buildResponseDto(entry));
        LocalDate newDate = LocalDate.of(2024, 3, 15);

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), new DiaryEntryUpdateDTO(
                null, null, newDate, null, null));

        verify(diaryEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getWatchedDate()).isEqualTo(newDate);
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Throw BadRequestException - When Updated WatchedDate Is Before The Content's Release Date From TMDB")
    void shouldThrowBadRequestExceptionWhenUpdatedWatchedDateIsBeforeTheContentsReleaseDateFromTmdb() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(tmdbClient.getMovieFullDetails(eq(fightClub.getTmdbId()), any())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbMovieFullDetails(null, null, null, null, null, null, "2030-01-01", null, null, null,
                        null, null, null, null, null, null, null)));

        assertThatThrownBy(() -> diaryEntryService.updateDiaryEntry(lucasId, entry.getId(),
                new DiaryEntryUpdateDTO(null, null, LocalDate.of(2024, 3, 15), null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("watchedDate cannot predate the content's release date");

        verify(diaryEntryRepository, never()).save(any());
        verify(userContentMutationLock, never()).lock(lucasId);
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Update WatchedInTheater - When A Different Value Is Provided")
    void shouldUpdateWatchedInTheaterWhenADifferentValueIsProvided() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        entry.setWatchedInTheater(false);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(entry);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false, List.of())).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), new DiaryEntryUpdateDTO(
                null, null, null, true, null));

        verify(diaryEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getWatchedInTheater()).isTrue();
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Throw BadRequestException - When WatchedInTheater Is Set And Content Type Is Not Movie")
    void shouldThrowBadRequestExceptionWhenWatchedInTheaterIsSetAndContentTypeIsNotMovieOnUpdate() {
        Content theOffice = buildContent("2316", ContentType.SERIES);
        DiaryEntry entry = buildEntry(lucas, theOffice);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), new DiaryEntryUpdateDTO(
                null, null, null, true, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("watchedInTheater can only be set for content of type MOVIE");

        verify(diaryEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Throw BadRequestException - When WatchedDate Is In The Future")
    void shouldThrowBadRequestExceptionWhenWatchedDateIsInTheFutureOnUpdate() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), new DiaryEntryUpdateDTO(
                null, null, LocalDate.now().plusDays(1), null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("watchedDate cannot be in the future");

        verify(diaryEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Update CustomPosterUrl - When A Different Value Is Provided")
    void shouldUpdateCustomPosterUrlWhenADifferentValueIsProvided() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(entry);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false, List.of())).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), new DiaryEntryUpdateDTO(
                null, null, null, null, "https://image.tmdb.org/t/p/w342/new.png"));

        verify(userContentPosterService).upsert(lucasId, fightClub.getId(), "https://image.tmdb.org/t/p/w342/new.png");
    }

    // ---------- deleteDiaryEntry ----------

    @Test
    @DisplayName("[deleteDiaryEntry] Should Delete Entry - When Owner Requests It")
    void shouldDeleteEntryWhenOwnerRequestsIt() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

        diaryEntryService.deleteDiaryEntry(lucasId, entry.getId());

        verify(userContentMutationLock).lock(lucasId);
        verify(diaryEntryRepository).delete(entry);
        verify(userContentPosterService, never()).delete(lucasId, fightClub.getId());
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Tombstone Imported Source Links - When Owner Deletes An Event")
    void shouldTombstoneImportedSourceLinksWhenOwnerDeletesAnEvent() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

        diaryEntryService.deleteDiaryEntry(lucasId, entry.getId());

        verify(importLedger).markDeletedByTarget(lucasId, "DIARY_ENTRY", entry.getId());
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Throw NotFoundException - When Entry Does Not Exist")
    void shouldThrowNotFoundExceptionWhenEntryDoesNotExistOnDelete() {
        UUID missingId = UUID.randomUUID();
        when(diaryEntryRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> diaryEntryService.deleteDiaryEntry(lucasId, missingId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Diary entry not found");

        verify(diaryEntryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Throw NotFoundException - When Entry Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenEntryBelongsToADifferentUserOnDelete() {
        DiaryEntry marinasEntry = buildEntry(marina, fightClub);
        when(diaryEntryRepository.findById(marinasEntry.getId())).thenReturn(Optional.of(marinasEntry));

        assertThatThrownBy(() -> diaryEntryService.deleteDiaryEntry(lucasId, marinasEntry.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Diary entry not found");

        verify(diaryEntryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Not Touch Season Or Series - When Deleting A Movie Entry")
    void shouldNotTouchSeasonOrSeriesWhenDeletingAMovieEntry() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

        diaryEntryService.deleteDiaryEntry(lucasId, entry.getId());

        verify(diaryEntryRepository).delete(entry);
        verify(contentRepository, never())
                .findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(any(), any(), any());
        verify(contentRepository, never())
                .findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue(any(), any());
    }

    // ---------- helpers ----------

    private void stubEmptyDiaryPage() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(any(), any(PageRequest.class)))
                .thenReturn(Page.empty());
    }

    private void stubContentResolution(Content content) {
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), anyBoolean()))
                .thenReturn(new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(), null, null, null,
                        null, null, LocalDateTime.now(), LocalDateTime.now()));
    }

    private DiaryEntryCreationDTO minimalCreationDto() {
        return new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null),
                null, null, null, null, null, null);
    }

    private DiaryEntryUpdateDTO minimalUpdateDto() {
        return new DiaryEntryUpdateDTO(null, null, null, null, null);
    }

    private DataIntegrityViolationException buildDataIntegrityViolationException(String constraintName) {
        ConstraintViolationException cve = new ConstraintViolationException(
                "constraint violated",
                null,
                constraintName
        );
        return new DataIntegrityViolationException("db error", cve);
    }

    private DiaryEntry buildEntry(User user, Content content) {
        LocalDateTime now = LocalDateTime.now();
        return DiaryEntry.builder()
                .id(UUID.randomUUID())
                .user(user)
                .content(content)
                .watchNumber(1)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private DiaryEntry buildDiaryEntry(User user, Content content, int watchNumber) {
        LocalDateTime now = LocalDateTime.now();
        return DiaryEntry.builder()
                .id(UUID.randomUUID())
                .user(user)
                .content(content)
                .watchNumber(watchNumber)
                .watchedDate(LocalDate.now())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private DiaryEntryResponseDTO buildResponseDto(DiaryEntry entry) {
        return new DiaryEntryResponseDTO(
                entry.getId(), entry.getUser().getId(), null, entry.getComment(), entry.getScore(),
                entry.getWatchedDate(), entry.getWatchNumber(), entry.getWatchedInTheater(),
                null, entry.getIgnore(), entry.getCreatedAt(), entry.getUpdatedAt(),
                entry.getLikesCount(), false);
    }

    private DiaryEntryResponseDTO buildResponseDtoWithContent(DiaryEntry entry) {
        Content content = entry.getContent();
        ContentRefDTO contentRef = new ContentRefDTO(
                content.getId(), content.getTmdbId(), content.getType(), content.getSeriesTmdbId(),
                content.getSeasonNumber(), content.getEpisodeNumber(), content.getIsSeasonFinale(),
                content.getIsSeriesFinale(), content.getCreatedAt(), content.getUpdatedAt());
        return new DiaryEntryResponseDTO(
                entry.getId(), entry.getUser().getId(), contentRef, entry.getComment(), entry.getScore(),
                entry.getWatchedDate(), entry.getWatchNumber(), entry.getWatchedInTheater(), null,
                entry.getIgnore(), entry.getCreatedAt(), entry.getUpdatedAt(),
                entry.getLikesCount(), false);
    }

    private DiaryEntryResponseDTO buildResponseDtoWithRuntime(DiaryEntry entry, Integer runtimeMinutes) {
        Content content = entry.getContent();
        ContentRefDTO contentRef = new ContentRefDTO(
                content.getId(), content.getTmdbId(), content.getType(), content.getSeriesTmdbId(),
                content.getSeasonNumber(), content.getEpisodeNumber(), content.getIsSeasonFinale(),
                content.getIsSeriesFinale(), content.getCreatedAt(), content.getUpdatedAt(), runtimeMinutes, null);
        return new DiaryEntryResponseDTO(
                entry.getId(), entry.getUser().getId(), contentRef, entry.getComment(), entry.getScore(),
                entry.getWatchedDate(), entry.getWatchNumber(), entry.getWatchedInTheater(), null,
                entry.getIgnore(), entry.getCreatedAt(), entry.getUpdatedAt(),
                entry.getLikesCount(), false);
    }

    private ContentCardDTO cardFor(Content content, String title, ContentPreviewStatus previewStatus) {
        return new ContentCardDTO(
                content.getId(), content.getType(), content.getTmdbId(), content.getSeriesTmdbId(),
                content.getSeasonNumber(), content.getEpisodeNumber(), title, "/poster.jpg", null,
                LocalDate.of(1999, 10, 15), 1999, content.getRuntimeMinutes(), null, null, null, null,
                null, null, previewStatus);
    }

    private Content buildContent(String tmdbId, ContentType type) {
        LocalDateTime now = LocalDateTime.now();
        Content content = Content.builder()
                .id(UUID.randomUUID())
                .tmdbId(tmdbId)
                .type(type)
                .createdAt(now)
                .updatedAt(now)
                .build();
        if (type == ContentType.SERIES) {
            preparedSeriesReferences.putIfAbsent(tmdbId, content);
        } else if (type == ContentType.MOVIE) {
            preparedMovieReferences.putIfAbsent(tmdbId, content);
        }
        return content;
    }

    private Content buildEpisode(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        Content content = Content.builder()
                .id(UUID.randomUUID())
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .type(ContentType.EPISODE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        preparedEpisodeReferences.putIfAbsent(episodeReferenceKey(seriesTmdbId, seasonNumber, episodeNumber), content);
        return content;
    }

    private Content buildFinaleEpisode(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        Content episode = buildEpisode(seriesTmdbId, seasonNumber, episodeNumber);
        episode.setIsSeasonFinale(true);
        return episode;
    }

    private Content buildSeason(String seriesTmdbId, Integer seasonNumber) {
        Content season = Content.builder()
                .id(UUID.randomUUID())
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .type(ContentType.SEASON)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        preparedSeasonReferences.putIfAbsent(seasonReferenceKey(seriesTmdbId, seasonNumber), season);
        return season;
    }

    private String seasonReferenceKey(String seriesTmdbId, Integer seasonNumber) {
        return seriesTmdbId + ":" + seasonNumber;
    }

    private Content buildFinaleSeason(String seriesTmdbId, Integer seasonNumber) {
        Content season = buildSeason(seriesTmdbId, seasonNumber);
        season.setIsSeriesFinale(true);
        return season;
    }

    private DiaryEntryRepository.ContentReviewKey reviewKey(DiaryEntry entry, ContentReviewSource source) {
        DiaryEntryRepository.ContentReviewKey key = mock(DiaryEntryRepository.ContentReviewKey.class);
        when(key.getReviewId()).thenReturn(entry.getId());
        when(key.getSource()).thenReturn(source.name());
        return key;
    }
}
