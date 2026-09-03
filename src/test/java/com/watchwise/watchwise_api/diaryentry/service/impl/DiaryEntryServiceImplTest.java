package com.watchwise.watchwise_api.diaryentry.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbGenre;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbProductionCountry;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.diaryentry.dto.DeletionImpactDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DeletionImpactItemDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryBulkCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationResultDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryUpdateDTO;
import com.watchwise.watchwise_api.diaryentry.dto.SeriesInProgressResponseDTO;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.entity.WatchCompanion;
import com.watchwise.watchwise_api.diaryentry.mapper.DiaryEntryMapper;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.diaryentry.repository.WatchCompanionRepository;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import com.watchwise.watchwise_api.watchlist.service.WatchlistEntryService;
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
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiaryEntryServiceImplTest {

    @Mock
    private DiaryEntryRepository diaryEntryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentService contentService;

    @Mock
    private FollowerRepository followerRepository;

    @Mock
    private DiaryEntryMapper diaryEntryMapper;

    @Mock
    private NewTransactionExecutor newTransactionExecutor;

    @Mock
    private WatchlistEntryService watchlistEntryService;

    @Mock
    private DroppedEntryRepository droppedEntryRepository;

    @Mock
    private LikeService likeService;

    @Mock
    private WatchCompanionRepository watchCompanionRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private TmdbClient tmdbClient;

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

    private UUID lucasId;
    private UUID marinaId;
    private User lucas;
    private User marina;
    private Content fightClub;

    @BeforeEach
    void setUp() {
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
        lenient().when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        lenient().when(tmdbClient.getSeasonFullDetails(any(), any(), any()))
                .thenReturn(new TmdbLookupResult.Found<>(emptySeasonDetails()));
        lenient().when(tmdbClient.getTvFullDetails(any(), any()))
                .thenReturn(new TmdbLookupResult.Found<>(emptySeriesDetails()));
    }

    private TmdbSeasonFullDetails emptySeasonDetails() {
        return new TmdbSeasonFullDetails(null, null, null, null, null, null, List.of(), null, null);
    }

    private TmdbTvFullDetails emptySeriesDetails() {
        return new TmdbTvFullDetails(null, null, null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, null, null, null, null, null);
    }

    private TmdbSeasonFullDetails seasonDetailsWithRuntimes(Map<Integer, Integer> runtimeByEpisode) {
        List<TmdbEpisodeSummary> episodes = runtimeByEpisode.entrySet().stream()
                .map(entry -> new TmdbEpisodeSummary(entry.getKey(), null, null, null, entry.getValue(), null, null))
                .toList();
        return new TmdbSeasonFullDetails(null, null, null, null, null, null, episodes, null, null);
    }

    // ---------- getDiaryEntries ----------

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
                eq(lucasId), isNull(), eq(LocalDate.of(2024, 1, 1)), eq(LocalDate.of(2024, 12, 31)), isNull(), any(PageRequest.class)))
                .thenReturn(Page.empty());

        diaryEntryService.getDiaryEntries(lucasId, lucasId, 2024, 1, 10, null, null, null, null);

        verify(diaryEntryRepository).findByUserIdWithFilters(
                eq(lucasId), isNull(), eq(LocalDate.of(2024, 1, 1)), eq(LocalDate.of(2024, 12, 31)), isNull(), any(PageRequest.class));
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
                eq(lucasId), eq(ContentType.EPISODE), isNull(), isNull(), isNull(), any(PageRequest.class)))
                .thenReturn(Page.empty());

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 10, ContentType.EPISODE, null, null, null);

        verify(diaryEntryRepository).findByUserIdWithFilters(
                eq(lucasId), eq(ContentType.EPISODE), isNull(), isNull(), isNull(), any(PageRequest.class));
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Query With Filters - When HasReview Is Provided")
    void shouldQueryWithFiltersWhenHasReviewIsProvided() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdWithFilters(
                eq(lucasId), isNull(), isNull(), isNull(), eq(true), any(PageRequest.class)))
                .thenReturn(Page.empty());

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 10, null, null, null, true);

        verify(diaryEntryRepository).findByUserIdWithFilters(
                eq(lucasId), isNull(), isNull(), isNull(), eq(true), any(PageRequest.class));
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
    @DisplayName("[getSeriesInProgress] Should Return Mapped Page - When Viewer Is The Profile Owner")
    void shouldReturnMappedPageWhenViewerIsTheProfileOwnerForSeriesInProgress() {
        DiaryEntryRepository.SeriesInProgress row = seriesInProgress("1399", 8, 6, LocalDate.of(2024, 5, 1));
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findSeriesInProgressByUserId(eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(row)));

        Page<SeriesInProgressResponseDTO> result = diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, 10);

        assertThat(result.getContent())
                .containsExactly(new SeriesInProgressResponseDTO("1399", 8, 6, LocalDate.of(2024, 5, 1)));
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Return Empty Page - When User Has No Series In Progress")
    void shouldReturnEmptyPageWhenUserHasNoSeriesInProgress() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findSeriesInProgressByUserId(eq(lucasId), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<SeriesInProgressResponseDTO> result = diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, 10);

        assertThat(result.getContent()).isEmpty();
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
        when(diaryEntryRepository.findSeriesInProgressByUserId(eq(lucasId), any(PageRequest.class)))
                .thenReturn(Page.empty());

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
        when(diaryEntryRepository.findSeriesInProgressByUserId(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());

        diaryEntryService.getSeriesInProgress(lucasId, lucasId, null, 10);

        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(diaryEntryRepository).findSeriesInProgressByUserId(eq(lucasId), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Use Page Number Minus One - When Page Number Is Positive")
    void shouldUsePageNumberMinusOneWhenPageNumberIsPositiveForSeriesInProgress() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findSeriesInProgressByUserId(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());

        diaryEntryService.getSeriesInProgress(lucasId, lucasId, 3, 10);

        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(diaryEntryRepository).findSeriesInProgressByUserId(eq(lucasId), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Throw BadRequestException - When Page Number Is Negative")
    void shouldThrowBadRequestExceptionWhenPageNumberIsNegativeForSeriesInProgress() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getSeriesInProgress(lucasId, lucasId, -1, 10))
                .isInstanceOf(BadRequestException.class);

        verify(diaryEntryRepository, never()).findSeriesInProgressByUserId(any(), any());
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Use Default Page Size - When Page Size Is Null")
    void shouldUseDefaultPageSizeWhenPageSizeIsNullForSeriesInProgress() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findSeriesInProgressByUserId(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());

        diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, null);

        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(diaryEntryRepository).findSeriesInProgressByUserId(eq(lucasId), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(PageRequestFactory.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Clamp Page Size To Max Limit - When Page Size Exceeds Limit")
    void shouldClampPageSizeToMaxLimitWhenPageSizeExceedsLimitForSeriesInProgress() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findSeriesInProgressByUserId(eq(lucasId), any(PageRequest.class))).thenReturn(Page.empty());

        diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, 1001);

        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(diaryEntryRepository).findSeriesInProgressByUserId(eq(lucasId), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(PageRequestFactory.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Throw BadRequestException - When Page Size Is Negative")
    void shouldThrowBadRequestExceptionWhenPageSizeIsNegativeForSeriesInProgress() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, -5))
                .isInstanceOf(BadRequestException.class);

        verify(diaryEntryRepository, never()).findSeriesInProgressByUserId(any(), any());
    }

    @Test
    @DisplayName("[getSeriesInProgress] Should Throw BadRequestException - When Page Size Is Zero")
    void shouldThrowBadRequestExceptionWhenPageSizeIsZeroForSeriesInProgress() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getSeriesInProgress(lucasId, lucasId, 1, 0))
                .isInstanceOf(BadRequestException.class);

        verify(diaryEntryRepository, never()).findSeriesInProgressByUserId(any(), any());
    }

    private DiaryEntryRepository.SeriesInProgress seriesInProgress(
            String seriesTmdbId, Integer maxSeasonNumber, Integer maxEpisodeNumber, LocalDate lastWatchedDate) {
        return new DiaryEntryRepository.SeriesInProgress() {
            @Override
            public String getSeriesTmdbId() {
                return seriesTmdbId;
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

        verify(diaryEntryRepository, never()).findReviewsByContentId(any(), any(), any());
    }

    @Test
    @DisplayName("[getReviewsForContent] Should Return Mapped Page - When Content Has Reviews Visible To The Viewer")
    void shouldReturnMappedPageWhenContentHasReviewsVisibleToTheViewer() {
        DiaryEntry entry = buildEntry(marina, fightClub);
        entry.setComment("Great movie");
        DiaryEntryResponseDTO dto = buildResponseDto(entry);
        when(contentRepository.existsById(fightClub.getId())).thenReturn(true);
        when(diaryEntryRepository.findReviewsByContentId(eq(fightClub.getId()), eq(lucasId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(entry)));
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false, List.of())).thenReturn(dto);

        Page<DiaryEntryResponseDTO> result = diaryEntryService.getReviewsForContent(lucasId, fightClub.getId(), 1, 10);

        assertThat(result.getContent()).containsExactly(dto);
    }

    @Test
    @DisplayName("[getReviewsForContent] Should Return Empty Page - When Content Has No Visible Reviews")
    void shouldReturnEmptyPageWhenContentHasNoVisibleReviews() {
        when(contentRepository.existsById(fightClub.getId())).thenReturn(true);
        when(diaryEntryRepository.findReviewsByContentId(eq(fightClub.getId()), eq(lucasId), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<DiaryEntryResponseDTO> result = diaryEntryService.getReviewsForContent(lucasId, fightClub.getId(), 1, 10);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[getReviewsForContent] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPageWhenPageNumberIsNullForReviews() {
        when(contentRepository.existsById(fightClub.getId())).thenReturn(true);
        when(diaryEntryRepository.findReviewsByContentId(eq(fightClub.getId()), eq(lucasId), any(PageRequest.class)))
                .thenReturn(Page.empty());

        diaryEntryService.getReviewsForContent(lucasId, fightClub.getId(), null, 10);

        verify(diaryEntryRepository).findReviewsByContentId(eq(fightClub.getId()), eq(lucasId), pageRequestCaptor.capture());
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
                "Great movie", 9, watchedDate, true, false, "https://example.com/poster.png");

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
        assertThat(captured.getCustomPosterUrl()).isEqualTo("https://example.com/poster.png");
        assertThat(captured.getIgnore()).isFalse();
        verify(contentService).getOrCreateReference(contentRefCreationCaptor.capture());
        assertThat(contentRefCreationCaptor.getValue())
                .isEqualTo(new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null));
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
    @DisplayName("[createDiaryEntry] Should Save A WatchCompanion - When A Followed Companion Is Provided")
    void shouldSaveAWatchCompanionWhenAFollowedCompanionIsProvided() {
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

        verify(watchCompanionRepository).saveAll(watchCompanionsCaptor.capture());
        List<WatchCompanion> saved = watchCompanionsCaptor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getUser()).isEqualTo(marina);
        assertThat(saved.get(0).getDiaryEntry()).isEqualTo(savedEntry);
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
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
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
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Ignore IsRewatch And Set WatchNumber To 1 - When No Prior Entry Exists And Content Type Is SEASON")
    void shouldIgnoreIsRewatchAndSetWatchNumberToOneWhenNoPriorEntryExistsAndContentTypeIsSeason() {
        Content season = buildSeason("900", 1);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenReturn(new ContentRefDTO(season.getId(), null, ContentType.SEASON, "900", 1, null, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(season.getId())).thenReturn(season);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, season.getId())).thenReturn(0);
        DiaryEntry savedEntry = buildEntry(lucas, season);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null),
                null, null, null, true, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryEntryRepository).saveAndFlush(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getWatchNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Honor IsRewatch And Set WatchNumber To 2 - When No Prior Entry Exists But Content Type Is SERIES")
    void shouldHonorIsRewatchAndSetWatchNumberToTwoWhenNoPriorEntryExistsButContentTypeIsSeries() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("900").type(ContentType.SERIES).build();
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenReturn(new ContentRefDTO(series.getId(), "900", ContentType.SERIES, null, null, null, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(series.getId())).thenReturn(series);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, series.getId())).thenReturn(0);
        DiaryEntry savedEntry = buildEntry(lucas, series);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry, false, List.of())).thenReturn(buildResponseDto(savedEntry));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null),
                null, null, null, true, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryEntryRepository).saveAndFlush(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getWatchNumber()).isEqualTo(2);
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

        verify(watchlistEntryService).removeEntryIfPresent(lucasId, ContentType.MOVIE, fightClub.getId());
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

        verify(droppedEntryRepository).delete(droppedEntry);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Resolve The Series And Remove Its Watchlist And Dropped Entries - When Logging An Episode")
    void shouldResolveTheSeriesAndRemoveItsWatchlistAndDroppedEntriesWhenLoggingAnEpisode() {
        Content episode = buildEpisode("900", 1, 1);
        Content seriesContent = buildContent("900", ContentType.SERIES);

        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
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

        verify(watchlistEntryService).removeEntryIfPresent(lucasId, ContentType.SERIES, seriesContent.getId());
        verify(droppedEntryRepository).findByUserIdAndTypeAndContentId(lucasId, ContentType.SERIES, seriesContent.getId());
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Not Attempt Removal - When The Series Content Does Not Exist Yet")
    void shouldNotAttemptRemovalWhenTheSeriesContentDoesNotExistYet() {
        Content episode = buildEpisode("900", 1, 1);

        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
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

        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
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

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(watchlistEntryService).removeEntryIfPresent(lucasId, ContentType.SERIES, seriesContent.getId());
        verify(contentRepository, never()).findByTmdbIdAndType(any(), any());
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
    @DisplayName("[createDiaryEntry] Should Auto-Create Season Entry With WatchNumber 1 - When The First Complete Pass Finishes")
    void shouldAutoCreateSeasonEntryWithWatchNumberOneWhenTheFirstCompletePassFinishes() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 2);
        Content season = buildSeason("1399", 1);

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(finaleEpisode);
        when(contentRepository.getReferenceById(finaleEpisode.getId())).thenReturn(finaleEpisode);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, finaleEpisode.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L), episodeWatchCount(2, 1L)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, null)))
                .thenReturn(new ContentRefDTO(season.getId(), null, ContentType.SEASON, "1399", 1, null, null, null, null, null));
        when(contentRepository.getReferenceById(season.getId())).thenReturn(season);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, season.getId())).thenReturn(0);
        stubNewTransactionPassthrough();
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, finaleEpisode)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 2, true, null),
                null, null, LocalDate.of(2024, 5, 1), null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        ArgumentCaptor<DiaryEntry> savedCaptor = ArgumentCaptor.forClass(DiaryEntry.class);
        verify(diaryEntryRepository, times(2)).saveAndFlush(savedCaptor.capture());
        DiaryEntry savedSeasonEntry = savedCaptor.getAllValues().get(1);
        assertThat(savedSeasonEntry.getContent()).isEqualTo(season);
        assertThat(savedSeasonEntry.getAutoGenerated()).isTrue();
        assertThat(savedSeasonEntry.getComment()).isNull();
        assertThat(savedSeasonEntry.getScore()).isNull();
        assertThat(savedSeasonEntry.getWatchedDate()).isEqualTo(LocalDate.of(2024, 5, 1));
        assertThat(savedSeasonEntry.getWatchNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Auto-Create A Second Season Entry With WatchNumber 2 - When A Full Rewatch Completes")
    void shouldAutoCreateASecondSeasonEntryWithWatchNumberTwoWhenAFullRewatchCompletes() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 2);
        Content season = buildSeason("1399", 1);

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(finaleEpisode);
        when(contentRepository.getReferenceById(finaleEpisode.getId())).thenReturn(finaleEpisode);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, finaleEpisode.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 2L), episodeWatchCount(2, 2L)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, null)))
                .thenReturn(new ContentRefDTO(season.getId(), null, ContentType.SEASON, "1399", 1, null, null, null, null, null));
        when(contentRepository.getReferenceById(season.getId())).thenReturn(season);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, season.getId())).thenReturn(1);
        stubNewTransactionPassthrough();
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, finaleEpisode)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 2, true, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        ArgumentCaptor<DiaryEntry> savedCaptor = ArgumentCaptor.forClass(DiaryEntry.class);
        verify(diaryEntryRepository, times(2)).saveAndFlush(savedCaptor.capture());
        DiaryEntry savedSeasonEntry = savedCaptor.getAllValues().get(1);
        assertThat(savedSeasonEntry.getWatchNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Not Auto-Create A Season Entry - When Only Some Episodes Have Been Rewatched")
    void shouldNotAutoCreateASeasonEntryWhenOnlySomeEpisodesHaveBeenRewatched() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 2);
        Content season = buildSeason("1399", 1);

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(finaleEpisode);
        when(contentRepository.getReferenceById(finaleEpisode.getId())).thenReturn(finaleEpisode);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, finaleEpisode.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 2L), episodeWatchCount(2, 1L)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, null)))
                .thenReturn(new ContentRefDTO(season.getId(), null, ContentType.SEASON, "1399", 1, null, null, null, null, null));
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, season.getId())).thenReturn(1);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, finaleEpisode)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 2, true, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryEntryRepository, times(1)).saveAndFlush(any(DiaryEntry.class));
        verifyNoInteractions(newTransactionExecutor);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Source IsSeriesFinale From The Season Finale Episode - When It Differs From The Triggering Request")
    void shouldSourceIsSeriesFinaleFromTheSeasonFinaleEpisodeWhenItDiffersFromTheTriggeringRequest() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 2);
        finaleEpisode.setIsSeriesFinale(true);
        Content nonFinaleEpisode = buildEpisode("1399", 1, 1);
        Content season = buildSeason("1399", 1);

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(nonFinaleEpisode);
        when(contentRepository.getReferenceById(nonFinaleEpisode.getId())).thenReturn(nonFinaleEpisode);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, nonFinaleEpisode.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L), episodeWatchCount(2, 1L)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, true)))
                .thenReturn(new ContentRefDTO(season.getId(), null, ContentType.SEASON, "1399", 1, null, null, true, null, null));
        when(contentRepository.getReferenceById(season.getId())).thenReturn(season);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, season.getId())).thenReturn(0);
        stubNewTransactionPassthrough();
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, nonFinaleEpisode)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 1, null, null),
                null, null, LocalDate.of(2024, 5, 1), null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(contentService).getOrCreateReference(new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, true));
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Not Auto-Create Season Entry - When Some Episodes Are Still Missing")
    void shouldNotAutoCreateSeasonEntryWhenSomeEpisodesAreStillMissing() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 5);

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(finaleEpisode);
        when(contentRepository.getReferenceById(finaleEpisode.getId())).thenReturn(finaleEpisode);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L), episodeWatchCount(2, 1L), episodeWatchCount(3, 1L)));
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, finaleEpisode)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 5, true, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryEntryRepository, times(1)).saveAndFlush(any(DiaryEntry.class));
        verify(contentService, times(1)).getOrCreateReference(any(ContentRefCreationDTO.class));
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Not Auto-Create Season Entry - When The Season Finale Was Never Referenced")
    void shouldNotAutoCreateSeasonEntryWhenTheSeasonFinaleWasNeverReferenced() {
        Content episode = buildEpisode("1399", 1, 1);

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(episode);
        when(contentRepository.getReferenceById(episode.getId())).thenReturn(episode);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, episode)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 1, null, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryEntryRepository, times(1)).saveAndFlush(any(DiaryEntry.class));
        verify(diaryEntryRepository, never()).countEntriesByEpisodeNumberInSeason(any(), any(), any());
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Auto-Create Series Entry With WatchNumber 1 - When The First Complete Pass Finishes")
    void shouldAutoCreateSeriesEntryWithWatchNumberOneWhenTheFirstCompletePassFinishes() {
        Content finaleSeason = buildFinaleSeason("1399", 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES).build();

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(finaleSeason);
        when(contentRepository.getReferenceById(finaleSeason.getId())).thenReturn(finaleSeason);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, finaleSeason.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.of(finaleSeason));
        when(diaryEntryRepository.maxWatchNumberBySeasonInSeries(lucasId, "1399"))
                .thenReturn(List.of(seasonWatchMax(1, 1)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO("1399", ContentType.SERIES, null, null, null, null, null)))
                .thenReturn(new ContentRefDTO(series.getId(), "1399", ContentType.SERIES, null, null, null, null, null, null, null));
        when(contentRepository.getReferenceById(series.getId())).thenReturn(series);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, series.getId())).thenReturn(0);
        stubNewTransactionPassthrough();
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, finaleSeason)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, true),
                null, null, LocalDate.of(2024, 6, 1), null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        ArgumentCaptor<DiaryEntry> savedCaptor = ArgumentCaptor.forClass(DiaryEntry.class);
        verify(diaryEntryRepository, times(2)).saveAndFlush(savedCaptor.capture());
        DiaryEntry savedSeriesEntry = savedCaptor.getAllValues().get(1);
        assertThat(savedSeriesEntry.getContent()).isEqualTo(series);
        assertThat(savedSeriesEntry.getAutoGenerated()).isTrue();
        assertThat(savedSeriesEntry.getWatchNumber()).isEqualTo(1);
        assertThat(savedSeriesEntry.getWatchedDate()).isEqualTo(LocalDate.of(2024, 6, 1));
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Auto-Create A Second Series Entry With WatchNumber 2 - When A Full Rewatch Completes")
    void shouldAutoCreateASecondSeriesEntryWithWatchNumberTwoWhenAFullRewatchCompletes() {
        Content finaleSeason = buildFinaleSeason("1399", 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES).build();

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(finaleSeason);
        when(contentRepository.getReferenceById(finaleSeason.getId())).thenReturn(finaleSeason);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, finaleSeason.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.of(finaleSeason));
        when(diaryEntryRepository.maxWatchNumberBySeasonInSeries(lucasId, "1399"))
                .thenReturn(List.of(seasonWatchMax(1, 2)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO("1399", ContentType.SERIES, null, null, null, null, null)))
                .thenReturn(new ContentRefDTO(series.getId(), "1399", ContentType.SERIES, null, null, null, null, null, null, null));
        when(contentRepository.getReferenceById(series.getId())).thenReturn(series);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, series.getId())).thenReturn(1);
        stubNewTransactionPassthrough();
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, finaleSeason)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, true),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        ArgumentCaptor<DiaryEntry> savedCaptor = ArgumentCaptor.forClass(DiaryEntry.class);
        verify(diaryEntryRepository, times(2)).saveAndFlush(savedCaptor.capture());
        DiaryEntry savedSeriesEntry = savedCaptor.getAllValues().get(1);
        assertThat(savedSeriesEntry.getWatchNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Not Auto-Create A Series Entry - When Only Some Seasons Have Been Rewatched")
    void shouldNotAutoCreateASeriesEntryWhenOnlySomeSeasonsHaveBeenRewatched() {
        Content finaleSeason = buildFinaleSeason("1399", 2);
        UUID seriesId = UUID.randomUUID();

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(finaleSeason);
        when(contentRepository.getReferenceById(finaleSeason.getId())).thenReturn(finaleSeason);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, finaleSeason.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.of(finaleSeason));
        when(diaryEntryRepository.maxWatchNumberBySeasonInSeries(lucasId, "1399"))
                .thenReturn(List.of(seasonWatchMax(1, 2), seasonWatchMax(2, 1)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO("1399", ContentType.SERIES, null, null, null, null, null)))
                .thenReturn(new ContentRefDTO(seriesId, "1399", ContentType.SERIES, null, null, null, null, null, null, null));
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, seriesId)).thenReturn(1);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, finaleSeason)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 2, null, null, true),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryEntryRepository, times(1)).saveAndFlush(any(DiaryEntry.class));
        verifyNoInteractions(newTransactionExecutor);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Not Auto-Create Series Entry - When Some Seasons Are Still Missing")
    void shouldNotAutoCreateSeriesEntryWhenSomeSeasonsAreStillMissing() {
        Content finaleSeason = buildFinaleSeason("1399", 5);

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(finaleSeason);
        when(contentRepository.getReferenceById(finaleSeason.getId())).thenReturn(finaleSeason);
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.of(finaleSeason));
        when(diaryEntryRepository.maxWatchNumberBySeasonInSeries(lucasId, "1399"))
                .thenReturn(List.of(seasonWatchMax(1, 1), seasonWatchMax(2, 1), seasonWatchMax(3, 1)));
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, finaleSeason)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 5, null, null, true),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryEntryRepository, times(1)).saveAndFlush(any(DiaryEntry.class));
        verify(contentService, times(1)).getOrCreateReference(any(ContentRefCreationDTO.class));
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Not Auto-Create Series Entry - When The Series Finale Was Never Referenced")
    void shouldNotAutoCreateSeriesEntryWhenTheSeriesFinaleWasNeverReferenced() {
        Content season = buildSeason("1399", 2);

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(season);
        when(contentRepository.getReferenceById(season.getId())).thenReturn(season);
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, season)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 2, null, null, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryEntryRepository, times(1)).saveAndFlush(any(DiaryEntry.class));
        verify(diaryEntryRepository, never()).maxWatchNumberBySeasonInSeries(any(), any());
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Not Auto-Create Season Entry - When The Season Finale EpisodeNumber Is Below One")
    void shouldNotAutoCreateSeasonEntryWhenTheSeasonFinaleEpisodeNumberIsBelowOne() {
        Content loggedEpisode = buildEpisode("1399", 1, 1);
        Content degenerateFinale = buildFinaleEpisode("1399", 1, 0);
        ContentRefCreationDTO contentRef = new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 1, null, null);

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentService.getOrCreateReference(contentRef)).thenReturn(new ContentRefDTO(
                loggedEpisode.getId(), null, ContentType.EPISODE, "1399", 1, 1, null, null,
                LocalDateTime.now(), LocalDateTime.now()));
        when(contentRepository.getReferenceById(loggedEpisode.getId())).thenReturn(loggedEpisode);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(degenerateFinale));
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, loggedEpisode)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(contentRef, null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryEntryRepository, times(1)).saveAndFlush(any(DiaryEntry.class));
        verify(diaryEntryRepository, never()).countEntriesByEpisodeNumberInSeason(any(), any(), any());
        verifyNoInteractions(newTransactionExecutor);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Not Auto-Create Series Entry - When The Series Finale SeasonNumber Is Below One")
    void shouldNotAutoCreateSeriesEntryWhenTheSeriesFinaleSeasonNumberIsBelowOne() {
        Content specialsSeason = buildFinaleSeason("1399", 0);
        ContentRefCreationDTO contentRef = new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 0, null, null, true);

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentService.getOrCreateReference(contentRef)).thenReturn(new ContentRefDTO(
                specialsSeason.getId(), null, ContentType.SEASON, "1399", 0, null, null, true,
                LocalDateTime.now(), LocalDateTime.now()));
        when(contentRepository.getReferenceById(specialsSeason.getId())).thenReturn(specialsSeason);
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.of(specialsSeason));
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, specialsSeason)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(contentRef, null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryEntryRepository, times(1)).saveAndFlush(any(DiaryEntry.class));
        verify(diaryEntryRepository, never()).maxWatchNumberBySeasonInSeries(any(), any());
        verifyNoInteractions(newTransactionExecutor);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Auto-Create Exactly Three Sequential Season Entries - When Three Complete Passes Are Pending At Once")
    void shouldAutoCreateExactlyThreeSequentialSeasonEntriesWhenThreeCompletePassesArePendingAtOnce() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 2);
        Content season = buildSeason("1399", 1);
        AtomicInteger persistedSeasonMax = new AtomicInteger(0);

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(finaleEpisode);
        when(contentRepository.getReferenceById(finaleEpisode.getId())).thenReturn(finaleEpisode);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, finaleEpisode.getId())).thenReturn(2);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 3L), episodeWatchCount(2, 3L)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, null)))
                .thenReturn(new ContentRefDTO(season.getId(), null, ContentType.SEASON, "1399", 1, null, null, null, null, null));
        when(contentRepository.getReferenceById(season.getId())).thenReturn(season);
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, season.getId()))
                .thenAnswer(invocation -> persistedSeasonMax.get());
        stubNewTransactionPassthrough();
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> {
            DiaryEntry saved = invocation.getArgument(0);
            if (season.getId().equals(saved.getContent().getId())) {
                persistedSeasonMax.set(saved.getWatchNumber());
            }
            return saved;
        });
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, finaleEpisode)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 2, true, null),
                null, null, LocalDate.of(2024, 5, 1), null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        ArgumentCaptor<DiaryEntry> savedCaptor = ArgumentCaptor.forClass(DiaryEntry.class);
        verify(diaryEntryRepository, times(4)).saveAndFlush(savedCaptor.capture());
        List<DiaryEntry> savedSeasonEntries = savedCaptor.getAllValues().stream()
                .filter(entry -> season.getId().equals(entry.getContent().getId()))
                .toList();
        assertThat(savedSeasonEntries).extracting(DiaryEntry::getWatchNumber).containsExactly(1, 2, 3);
        assertThat(savedSeasonEntries).allSatisfy(entry -> assertThat(entry.getAutoGenerated()).isTrue());
        verify(newTransactionExecutor, times(3)).runInNewTransaction(any());
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Auto-Create Exactly Three Sequential Series Entries - When Three Complete Passes Are Pending At Once")
    void shouldAutoCreateExactlyThreeSequentialSeriesEntriesWhenThreeCompletePassesArePendingAtOnce() {
        Content finaleSeason = buildFinaleSeason("1399", 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES).build();
        AtomicInteger persistedSeriesMax = new AtomicInteger(0);

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(finaleSeason);
        when(contentRepository.getReferenceById(finaleSeason.getId())).thenReturn(finaleSeason);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, finaleSeason.getId())).thenReturn(2);
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.of(finaleSeason));
        when(diaryEntryRepository.maxWatchNumberBySeasonInSeries(lucasId, "1399"))
                .thenReturn(List.of(seasonWatchMax(1, 3)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO("1399", ContentType.SERIES, null, null, null, null, null)))
                .thenReturn(new ContentRefDTO(series.getId(), "1399", ContentType.SERIES, null, null, null, null, null, null, null));
        when(contentRepository.getReferenceById(series.getId())).thenReturn(series);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, series.getId()))
                .thenAnswer(invocation -> persistedSeriesMax.get());
        stubNewTransactionPassthrough();
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> {
            DiaryEntry saved = invocation.getArgument(0);
            if (series.getId().equals(saved.getContent().getId())) {
                persistedSeriesMax.set(saved.getWatchNumber());
            }
            return saved;
        });
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, finaleSeason)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, true),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        ArgumentCaptor<DiaryEntry> savedCaptor = ArgumentCaptor.forClass(DiaryEntry.class);
        verify(diaryEntryRepository, times(4)).saveAndFlush(savedCaptor.capture());
        List<DiaryEntry> savedSeriesEntries = savedCaptor.getAllValues().stream()
                .filter(entry -> series.getId().equals(entry.getContent().getId()))
                .toList();
        assertThat(savedSeriesEntries).extracting(DiaryEntry::getWatchNumber).containsExactly(1, 2, 3);
        assertThat(savedSeriesEntries).allSatisfy(entry -> assertThat(entry.getAutoGenerated()).isTrue());
        verify(newTransactionExecutor, times(3)).runInNewTransaction(any());
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Recursively Auto-Create Season And Series Entries - When Logging The Last Episode Completes Both")
    void shouldRecursivelyAutoCreateSeasonAndSeriesEntriesWhenLoggingTheLastEpisodeCompletesBoth() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 1);
        finaleEpisode.setIsSeriesFinale(true);
        Content season = buildFinaleSeason("1399", 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES).build();

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(finaleEpisode);
        when(contentRepository.getReferenceById(finaleEpisode.getId())).thenReturn(finaleEpisode);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, finaleEpisode.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, true)))
                .thenReturn(new ContentRefDTO(season.getId(), null, ContentType.SEASON, "1399", 1, null, null, true, null, null));
        when(contentRepository.getReferenceById(season.getId())).thenReturn(season);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, season.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(diaryEntryRepository.maxWatchNumberBySeasonInSeries(lucasId, "1399"))
                .thenReturn(List.of(seasonWatchMax(1, 1)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO("1399", ContentType.SERIES, null, null, null, null, null)))
                .thenReturn(new ContentRefDTO(series.getId(), "1399", ContentType.SERIES, null, null, null, null, null, null, null));
        when(contentRepository.getReferenceById(series.getId())).thenReturn(series);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, series.getId())).thenReturn(0);
        stubNewTransactionPassthrough();
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, finaleEpisode)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 1, true, true),
                null, null, LocalDate.of(2024, 7, 1), null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        ArgumentCaptor<DiaryEntry> savedCaptor = ArgumentCaptor.forClass(DiaryEntry.class);
        verify(diaryEntryRepository, times(3)).saveAndFlush(savedCaptor.capture());
        DiaryEntry savedEpisodeEntry = savedCaptor.getAllValues().get(0);
        DiaryEntry savedSeasonEntry = savedCaptor.getAllValues().get(1);
        DiaryEntry savedSeriesEntry = savedCaptor.getAllValues().get(2);
        assertThat(savedEpisodeEntry.getContent()).isEqualTo(finaleEpisode);
        assertThat(savedEpisodeEntry.getAutoGenerated()).isFalse();
        assertThat(savedSeasonEntry.getContent()).isEqualTo(season);
        assertThat(savedSeasonEntry.getAutoGenerated()).isTrue();
        assertThat(savedSeasonEntry.getWatchNumber()).isEqualTo(1);
        assertThat(savedSeriesEntry.getContent()).isEqualTo(series);
        assertThat(savedSeriesEntry.getAutoGenerated()).isTrue();
        assertThat(savedSeriesEntry.getWatchNumber()).isEqualTo(1);
        assertThat(savedSeriesEntry.getWatchedDate()).isEqualTo(LocalDate.of(2024, 7, 1));
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Return Null CompletedSeason - When The Season Is Not Completed By This Call")
    void shouldReturnNullCompletedSeasonWhenTheSeasonIsNotCompletedByThisCall() throws Exception {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 2);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L), episodeWatchCount(2, 0L)));

        CompletionSignalResult signal = invokeTriggerCompletionCascade(lucasId, finaleEpisode, LocalDate.of(2024, 5, 1));

        assertThat(signal.completedSeason()).isNull();
        assertThat(signal.completedSeries()).isNull();
        verifyNoInteractions(newTransactionExecutor);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Return The Auto-Generated Season Entry As CompletedSeason - When Logging The Episode Completes The Season")
    void shouldReturnTheAutoGeneratedSeasonEntryAsCompletedSeasonWhenLoggingTheEpisodeCompletesTheSeason() throws Exception {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 2);
        Content season = buildSeason("1399", 1);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L), episodeWatchCount(2, 1L)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, null)))
                .thenReturn(new ContentRefDTO(season.getId(), null, ContentType.SEASON, "1399", 1, null, null, null, null, null));
        when(contentRepository.getReferenceById(season.getId())).thenReturn(season);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, season.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubNewTransactionPassthrough();
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CompletionSignalResult signal = invokeTriggerCompletionCascade(lucasId, finaleEpisode, LocalDate.of(2024, 5, 1));

        assertThat(signal.completedSeason()).isNotNull();
        assertThat(signal.completedSeason().getContent()).isEqualTo(season);
        assertThat(signal.completedSeason().getAutoGenerated()).isTrue();
        assertThat(signal.completedSeason().getWatchNumber()).isEqualTo(1);
        assertThat(signal.completedSeason().getIgnore()).isFalse();
        assertThat(signal.completedSeries()).isNull();
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Save The Shared Companion On The Completed Season - When All Its Episodes Were Watched With The Same Person")
    void shouldSaveTheSharedCompanionOnTheCompletedSeasonWhenAllItsEpisodesWereWatchedWithTheSamePerson() throws Exception {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 2);
        Content otherEpisode = buildEpisode("1399", 1, 1);
        Content season = buildSeason("1399", 1);
        DiaryEntry firstEpisodeEntry = buildDiaryEntry(lucas, otherEpisode, 1);
        DiaryEntry secondEpisodeEntry = buildDiaryEntry(lucas, finaleEpisode, 1);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L), episodeWatchCount(2, 1L)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, null)))
                .thenReturn(new ContentRefDTO(season.getId(), null, ContentType.SEASON, "1399", 1, null, null, null, null, null));
        when(contentRepository.getReferenceById(season.getId())).thenReturn(season);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, season.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(userRepository.getReferenceById(marinaId)).thenReturn(marina);
        stubNewTransactionPassthrough();
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryRepository.findEpisodeEntriesInSeasonByWatchNumber(lucasId, "1399", 1, 1))
                .thenReturn(List.of(firstEpisodeEntry, secondEpisodeEntry));
        when(watchCompanionRepository.findByDiaryEntryIdIn(List.of(firstEpisodeEntry.getId(), secondEpisodeEntry.getId())))
                .thenReturn(List.of(
                        WatchCompanion.builder().diaryEntry(firstEpisodeEntry).user(marina).createdAt(LocalDateTime.now()).build(),
                        WatchCompanion.builder().diaryEntry(secondEpisodeEntry).user(marina).createdAt(LocalDateTime.now()).build()));

        invokeTriggerCompletionCascade(lucasId, finaleEpisode, LocalDate.of(2024, 5, 1));

        verify(watchCompanionRepository).saveAll(watchCompanionsCaptor.capture());
        assertThat(watchCompanionsCaptor.getValue()).hasSize(1);
        assertThat(watchCompanionsCaptor.getValue().get(0).getUser()).isEqualTo(marina);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Not Save Any Companion On The Completed Season - When Its Episodes Were Watched With Different People")
    void shouldNotSaveAnyCompanionOnTheCompletedSeasonWhenItsEpisodesWereWatchedWithDifferentPeople() throws Exception {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 2);
        Content otherEpisode = buildEpisode("1399", 1, 1);
        Content season = buildSeason("1399", 1);
        User joao = User.builder().id(UUID.randomUUID()).username("joao").email("joao@email.com").password("hashed_password")
                .isProfilePublic(true).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        DiaryEntry firstEpisodeEntry = buildDiaryEntry(lucas, otherEpisode, 1);
        DiaryEntry secondEpisodeEntry = buildDiaryEntry(lucas, finaleEpisode, 1);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L), episodeWatchCount(2, 1L)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, null)))
                .thenReturn(new ContentRefDTO(season.getId(), null, ContentType.SEASON, "1399", 1, null, null, null, null, null));
        when(contentRepository.getReferenceById(season.getId())).thenReturn(season);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, season.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubNewTransactionPassthrough();
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryRepository.findEpisodeEntriesInSeasonByWatchNumber(lucasId, "1399", 1, 1))
                .thenReturn(List.of(firstEpisodeEntry, secondEpisodeEntry));
        when(watchCompanionRepository.findByDiaryEntryIdIn(List.of(firstEpisodeEntry.getId(), secondEpisodeEntry.getId())))
                .thenReturn(List.of(
                        WatchCompanion.builder().diaryEntry(firstEpisodeEntry).user(marina).createdAt(LocalDateTime.now()).build(),
                        WatchCompanion.builder().diaryEntry(secondEpisodeEntry).user(joao).createdAt(LocalDateTime.now()).build()));

        CompletionSignalResult signal = invokeTriggerCompletionCascade(lucasId, finaleEpisode, LocalDate.of(2024, 5, 1));

        assertThat(signal.completedSeason()).isNotNull();
        verify(watchCompanionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Return Both CompletedSeason And CompletedSeries - When Logging The Episode Completes Both In The Same Call")
    void shouldReturnBothCompletedSeasonAndCompletedSeriesWhenLoggingTheEpisodeCompletesBothInTheSameCall() throws Exception {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 1);
        finaleEpisode.setIsSeriesFinale(true);
        Content season = buildFinaleSeason("1399", 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES).build();

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, true)))
                .thenReturn(new ContentRefDTO(season.getId(), null, ContentType.SEASON, "1399", 1, null, null, true, null, null));
        when(contentRepository.getReferenceById(season.getId())).thenReturn(season);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, season.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(diaryEntryRepository.maxWatchNumberBySeasonInSeries(lucasId, "1399"))
                .thenReturn(List.of(seasonWatchMax(1, 1)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO("1399", ContentType.SERIES, null, null, null, null, null)))
                .thenReturn(new ContentRefDTO(series.getId(), "1399", ContentType.SERIES, null, null, null, null, null, null, null));
        when(contentRepository.getReferenceById(series.getId())).thenReturn(series);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, series.getId())).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubNewTransactionPassthrough();
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CompletionSignalResult signal = invokeTriggerCompletionCascade(lucasId, finaleEpisode, LocalDate.of(2024, 7, 1));

        assertThat(signal.completedSeason()).isNotNull();
        assertThat(signal.completedSeason().getContent()).isEqualTo(season);
        assertThat(signal.completedSeason().getWatchNumber()).isEqualTo(1);
        assertThat(signal.completedSeason().getIgnore()).isFalse();
        assertThat(signal.completedSeries()).isNotNull();
        assertThat(signal.completedSeries().getContent()).isEqualTo(series);
        assertThat(signal.completedSeries().getWatchNumber()).isEqualTo(1);
        assertThat(signal.completedSeries().getIgnore()).isFalse();
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Ignore Only The Below-Requested Levels - When A Season-Level Request Cascades To Complete The Series")
    void shouldIgnoreOnlyTheBelowRequestedLevelsWhenASeasonLevelRequestCascadesToCompleteTheSeries() throws Exception {
        Content season = buildFinaleSeason("1399", 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES).build();

        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(diaryEntryRepository.maxWatchNumberBySeasonInSeries(lucasId, "1399"))
                .thenReturn(List.of(seasonWatchMax(1, 1)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO("1399", ContentType.SERIES, null, null, null, null, null)))
                .thenReturn(new ContentRefDTO(series.getId(), "1399", ContentType.SERIES, null, null, null, null, null, null, null));
        when(contentRepository.getReferenceById(series.getId())).thenReturn(series);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, series.getId())).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubNewTransactionPassthrough();
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CompletionSignalResult signal = invokeTriggerCompletionCascade(lucasId, season, LocalDate.of(2024, 7, 1), ContentType.SEASON);

        assertThat(signal.completedSeries()).isNotNull();
        assertThat(signal.completedSeries().getIgnore()).isFalse();
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Map CompletedSeason And CompletedSeries To Response DTOs - When Both Complete In The Same Call")
    void shouldMapCompletedSeasonAndCompletedSeriesToResponseDtosWhenBothCompleteInTheSameCall() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 1);
        finaleEpisode.setIsSeriesFinale(true);
        Content season = buildFinaleSeason("1399", 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES).build();

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(finaleEpisode);
        when(contentRepository.getReferenceById(finaleEpisode.getId())).thenReturn(finaleEpisode);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, finaleEpisode.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, true)))
                .thenReturn(new ContentRefDTO(season.getId(), null, ContentType.SEASON, "1399", 1, null, null, true, null, null));
        when(contentRepository.getReferenceById(season.getId())).thenReturn(season);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, season.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(diaryEntryRepository.maxWatchNumberBySeasonInSeries(lucasId, "1399"))
                .thenReturn(List.of(seasonWatchMax(1, 1)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO("1399", ContentType.SERIES, null, null, null, null, null)))
                .thenReturn(new ContentRefDTO(series.getId(), "1399", ContentType.SERIES, null, null, null, null, null, null, null));
        when(contentRepository.getReferenceById(series.getId())).thenReturn(series);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, series.getId())).thenReturn(0);
        stubNewTransactionPassthrough();
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 1, true, true),
                null, null, LocalDate.of(2024, 7, 1), null, null, null);

        DiaryEntryCreationResultDTO result = diaryEntryService.createDiaryEntry(lucasId, dto);

        assertThat(result.entry().autoGenerated()).isFalse();
        assertThat(result.completedSeason()).isNotNull();
        assertThat(result.completedSeason().watchNumber()).isEqualTo(1);
        assertThat(result.completedSeason().autoGenerated()).isTrue();
        assertThat(result.completedSeries()).isNotNull();
        assertThat(result.completedSeries().watchNumber()).isEqualTo(1);
        assertThat(result.completedSeries().autoGenerated()).isTrue();
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Recover And Use The Existing Season Entry - When The Auto-Generated Save Fails Due To Concurrent Creation")
    void shouldRecoverAndUseTheExistingSeasonEntryWhenTheAutoGeneratedSaveFailsDueToConcurrentCreation() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 1);
        Content season = buildSeason("1399", 1);
        DiaryEntry existingSeasonEntry = buildEntry(lucas, season);
        existingSeasonEntry.setWatchNumber(1);
        existingSeasonEntry.setAutoGenerated(true);

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(finaleEpisode);
        when(contentRepository.getReferenceById(finaleEpisode.getId())).thenReturn(finaleEpisode);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, finaleEpisode.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, null)))
                .thenReturn(new ContentRefDTO(season.getId(), null, ContentType.SEASON, "1399", 1, null, null, null, null, null));
        when(contentRepository.getReferenceById(season.getId())).thenReturn(season);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, season.getId())).thenReturn(0);
        when(newTransactionExecutor.runInNewTransaction(any()))
                .thenThrow(buildDataIntegrityViolationException("uq_diary_entries_user_content_watch_number"));
        when(diaryEntryRepository.findFirstByUserIdAndContentIdAndWatchNumber(lucasId, season.getId(), 1))
                .thenReturn(Optional.of(existingSeasonEntry));
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(buildEntry(lucas, finaleEpisode)));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 1, true, null),
                null, null, LocalDate.of(2024, 5, 1), null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryEntryRepository, times(1)).saveAndFlush(any(DiaryEntry.class));
        verify(diaryEntryRepository).findFirstByUserIdAndContentIdAndWatchNumber(lucasId, season.getId(), 1);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Propagate The Original Exception - When The Auto-Generated Save Fails And The Expected Entry Still Cannot Be Found")
    void shouldPropagateTheOriginalExceptionWhenTheAutoGeneratedSaveFailsAndTheExpectedEntryStillCannotBeFound() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 1);
        Content season = buildSeason("1399", 1);
        DataIntegrityViolationException exception = buildDataIntegrityViolationException("uq_diary_entries_user_content_watch_number");

        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubContentResolution(finaleEpisode);
        when(contentRepository.getReferenceById(finaleEpisode.getId())).thenReturn(finaleEpisode);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, finaleEpisode.getId())).thenReturn(0);
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L)));
        when(contentService.getOrCreateReference(new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, null)))
                .thenReturn(new ContentRefDTO(season.getId(), null, ContentType.SEASON, "1399", 1, null, null, null, null, null));
        when(contentRepository.getReferenceById(season.getId())).thenReturn(season);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, season.getId())).thenReturn(0);
        when(newTransactionExecutor.runInNewTransaction(any())).thenThrow(exception);
        when(diaryEntryRepository.findFirstByUserIdAndContentIdAndWatchNumber(lucasId, season.getId(), 1))
                .thenReturn(Optional.empty());
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(null, ContentType.EPISODE, "1399", 1, 1, true, null),
                null, null, LocalDate.of(2024, 5, 1), null, null, null);

        assertThatThrownBy(() -> diaryEntryService.createDiaryEntry(lucasId, dto))
                .isSameAs(exception);
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
    @DisplayName("[createDiaryEntriesInBulk] Should Backfill SERIES Genres, ReleaseYear And Countries From TMDB - When Content Has No Metadata Yet")
    void shouldBackfillSeriesGenresReleaseYearAndCountriesFromTmdbWhenContentHasNoMetadataYet() {
        Content e1 = buildFinaleEpisode("900", 1, 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("900").type(ContentType.SERIES).build();

        when(contentRepository.findByTmdbIdAndType("900", ContentType.SERIES)).thenReturn(Optional.of(series));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(tmdbClient.getTvFullDetails("900", lucas.getPreferredLanguage())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbTvFullDetails(null, null, null, null, null, null, "2008-01-20", null,
                        List.of(new TmdbGenre(80, "Crime"), new TmdbGenre(18, "Drama")),
                        List.of(new TmdbProductionCountry("US", "United States")),
                        null, List.of(), null, null, null, null, null, null, null, null)));
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.Found<>(seasonDetailsWithRuntimes(Map.of(1, 47))));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenReturn(new ContentRefDTO(e1.getId(), e1.getTmdbId(), ContentType.EPISODE, "900", 1, 1, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenReturn(new ContentRefDTO(series.getId(), "900", ContentType.SERIES, null, null, null, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentRepository.getReferenceById(e1.getId())).thenReturn(e1);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seriesRef, LocalDate.now(), null, 1, Map.of(1, 1));

        diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        verify(contentService).getOrCreateReference(contentRefCreationCaptor.capture());
        ContentRefCreationDTO captured = contentRefCreationCaptor.getValue();
        assertThat(captured.type()).isEqualTo(ContentType.SERIES);
        assertThat(captured.tmdbId()).isEqualTo("900");
        assertThat(captured.genres()).containsExactly("Crime", "Drama");
        assertThat(captured.releaseYear()).isEqualTo(2008);
        assertThat(captured.countries()).containsExactly("US");
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Not Call TMDB For SERIES Metadata - When Content Already Has Genres, ReleaseYear And Countries")
    void shouldNotCallTmdbForSeriesMetadataWhenContentAlreadyHasGenresReleaseYearAndCountries() {
        Content e1 = buildFinaleEpisode("900", 1, 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("900").type(ContentType.SERIES)
                .genres(List.of("Crime", "Drama")).releaseYear(2008).countries(List.of("US")).build();

        when(contentRepository.findByTmdbIdAndType("900", ContentType.SERIES)).thenReturn(Optional.of(series));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.Found<>(seasonDetailsWithRuntimes(Map.of(1, 47))));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenReturn(new ContentRefDTO(e1.getId(), e1.getTmdbId(), ContentType.EPISODE, "900", 1, 1, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentRepository.getReferenceById(e1.getId())).thenReturn(e1);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seriesRef, LocalDate.now(), null, 1, Map.of(1, 1));

        diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        verify(tmdbClient, never()).getTvFullDetails(any(), any());
        verify(contentService, never()).getOrCreateReference(any(ContentRefCreationDTO.class));
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Complete The Bulk Log Normally - When SERIES Metadata Cannot Be Fetched From TMDB")
    void shouldCompleteTheBulkLogNormallyWhenSeriesMetadataCannotBeFetchedFromTmdb() {
        Content e1 = buildFinaleEpisode("900", 1, 1);

        when(contentRepository.findByTmdbIdAndType("900", ContentType.SERIES)).thenReturn(Optional.empty());
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(tmdbClient.getTvFullDetails("900", lucas.getPreferredLanguage())).thenReturn(new TmdbLookupResult.Unavailable<>());
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.Found<>(seasonDetailsWithRuntimes(Map.of(1, 47))));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenReturn(new ContentRefDTO(e1.getId(), e1.getTmdbId(), ContentType.EPISODE, "900", 1, 1, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentRepository.getReferenceById(e1.getId())).thenReturn(e1);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seriesRef, LocalDate.now(), null, 1, Map.of(1, 1));

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        assertThat(result).hasSize(1);
        verify(contentService, never()).getOrCreateReference(any(ContentRefCreationDTO.class));
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Complete The Bulk Log Normally - When SERIES Metadata Backfill Conflicts With An Already Registered Value")
    void shouldCompleteTheBulkLogNormallyWhenSeriesMetadataBackfillConflictsWithAnAlreadyRegisteredValue() {
        Content e1 = buildFinaleEpisode("900", 1, 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("900").type(ContentType.SERIES).build();

        when(contentRepository.findByTmdbIdAndType("900", ContentType.SERIES)).thenReturn(Optional.of(series));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(tmdbClient.getTvFullDetails("900", lucas.getPreferredLanguage())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbTvFullDetails(null, null, null, null, null, null, "2008-01-20", null,
                        List.of(new TmdbGenre(80, "Crime")), null,
                        null, List.of(), null, null, null, null, null, null, null, null)));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenThrow(new ConflictException("This content is already registered with a different genres value"));
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.Found<>(seasonDetailsWithRuntimes(Map.of(1, 47))));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true)))
                .thenReturn(new ContentRefDTO(e1.getId(), e1.getTmdbId(), ContentType.EPISODE, "900", 1, 1, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentRepository.getReferenceById(e1.getId())).thenReturn(e1);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seriesRef, LocalDate.now(), null, 1, Map.of(1, 1));

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        assertThat(result).hasSize(1);
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
    @DisplayName("[createDiaryEntriesInBulk] Should Throw BadRequestException - When WatchedDate Predates The Finale Episode's Release Date")
    void shouldThrowBadRequestExceptionWhenWatchedDatePredatesTheFinaleEpisodesReleaseDateOnSeasonBulk() {
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
                .hasMessage("watchedDate cannot predate the content's release date (2020-01-08)");

        verify(contentService, never()).getOrCreateReference(any());
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
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(false)))
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
        verify(contentService, times(totalEpisodes)).getOrCreateReference(any(ContentRefCreationDTO.class), eq(false));
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
                        List.of(), null, null, null, null, null, totalEpisodes, null, null)));
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
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
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);

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
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);

        // Mock contentService and contentRepository to work together
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

    // ---------- createDiaryEntriesInBulk: watchlist/dropped removal side effect ----------

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Remove The Series Watchlist And Dropped Entries - When Bulk-Logging A Season")
    void shouldRemoveTheSeriesWatchlistAndDroppedEntriesWhenBulkLoggingASeason() {
        Content e1 = buildFinaleEpisode("900", 1, 1);
        Content seriesContent = buildContent("900", ContentType.SERIES);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(false)))
                .thenReturn(new ContentRefDTO(e1.getId(), e1.getTmdbId(), ContentType.EPISODE, "900", 1, 1, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentRepository.getReferenceById(e1.getId())).thenReturn(e1);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));
        when(contentRepository.findByTmdbIdAndType("900", ContentType.SERIES)).thenReturn(Optional.of(seriesContent));

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.now(), 1, null, null);

        diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        verify(watchlistEntryService).removeEntryIfPresent(lucasId, ContentType.SERIES, seriesContent.getId());
        verify(droppedEntryRepository).findByUserIdAndTypeAndContentId(lucasId, ContentType.SERIES, seriesContent.getId());
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
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
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

        verify(watchlistEntryService).removeEntryIfPresent(lucasId, ContentType.SERIES, seriesContent.getId());
        verify(droppedEntryRepository).findByUserIdAndTypeAndContentId(lucasId, ContentType.SERIES, seriesContent.getId());
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Apply Client-Supplied EpisodeRuntimeMinutes To The Content Reference - When Bulk-Logging A Season")
    void shouldApplyClientSuppliedEpisodeRuntimeMinutesWhenBulkLoggingASeason() {
        Content e1 = buildEpisode("900", 1, 1);
        Content e2 = buildFinaleEpisode("900", 1, 2);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.Found<>(seasonDetailsWithRuntimes(Map.of(1, 999, 2, 999))));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(false)))
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
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(
                seasonRef, LocalDate.now(), 2, null, null, null, Map.of(1, 47, 2, 55));

        diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        verify(contentService, times(2)).getOrCreateReference(contentRefCreationCaptor.capture(), eq(false));
        Map<Integer, Integer> runtimeByEpisode = contentRefCreationCaptor.getAllValues().stream()
                .collect(Collectors.toMap(ContentRefCreationDTO::episodeNumber, ContentRefCreationDTO::runtimeMinutes));
        assertThat(runtimeByEpisode).containsEntry(1, 47).containsEntry(2, 55);
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Omit RuntimeMinutes For An Episode Missing From episodeRuntimeMinutes - When Bulk-Logging A Season")
    void shouldOmitRuntimeMinutesForAnEpisodeMissingFromEpisodeRuntimeMinutesWhenBulkLoggingASeason() {
        Content e1 = buildFinaleEpisode("900", 1, 1);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.Found<>(seasonDetailsWithRuntimes(Map.of())));
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(false)))
                .thenReturn(new ContentRefDTO(e1.getId(), e1.getTmdbId(), ContentType.EPISODE, "900", 1, 1, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(contentRepository.getReferenceById(e1.getId())).thenReturn(e1);
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenAnswer(invocation -> buildResponseDto(invocation.getArgument(0)));

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(
                seasonRef, LocalDate.now(), 1, null, null, null, null);

        diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        verify(contentService).getOrCreateReference(contentRefCreationCaptor.capture(), eq(false));
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
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage()))
                .thenReturn(new TmdbLookupResult.Found<>(seasonDetailsWithRuntimes(Map.of(1, 47))));
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
    @DisplayName("[createDiaryEntriesInBulk] Should Derive FinaleEpisodeNumber From TMDB Aired Episodes - When Omitted And No Existing Finale Content")
    void shouldDeriveFinaleEpisodeNumberFromTmdbAiredEpisodesWhenOmittedAndNoExistingFinaleContent() {
        Content e1 = buildEpisode("900", 1, 1);
        Content e2 = buildEpisode("900", 1, 2);
        Content e3 = buildEpisode("900", 1, 3);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());
        when(tmdbClient.getSeasonFullDetails("900", 1, lucas.getPreferredLanguage())).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSeasonFullDetails(null, null, null, null, null, null, List.of(
                        new TmdbEpisodeSummary(1, null, null, "2020-01-01", 45, null, null),
                        new TmdbEpisodeSummary(2, null, null, "2020-01-08", 45, null, null),
                        new TmdbEpisodeSummary(3, null, null, "2020-01-15", 45, null, null),
                        new TmdbEpisodeSummary(4, null, null, "2099-01-01", 45, null, null)),
                        null, null)));
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(false)))
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
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.now(), null, null, null);

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        assertThat(result).hasSize(3);
        verify(contentService, times(3)).getOrCreateReference(any(ContentRefCreationDTO.class), eq(false));
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
                        null, null, null, null, null, null, null, null)));
        when(tmdbClient.getSeasonFullDetails(eq("900"), any(), eq(lucas.getPreferredLanguage()))).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSeasonFullDetails(null, null, null, null, null, null, List.of(
                        new TmdbEpisodeSummary(1, null, null, "2020-01-01", 45, null, null)),
                        null, null)));
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
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
    @DisplayName("[createDiaryEntriesInBulk] Should Create A Fresh Pass For Every Episode - When Some Already Have Watch Records")
    void shouldCreateAFreshPassForEveryEpisodeWhenSomeAlreadyHaveWatchRecords() {
        Content e1 = buildFinaleEpisode("900", 1, 3);
        Content e2 = buildEpisode("900", 1, 2);
        Content e3 = buildEpisode("900", 1, 1);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(e1));
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, e1.getId())).thenReturn(2);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, e2.getId())).thenReturn(0);
        when(diaryEntryRepository.findMaxWatchNumber(lucasId, e3.getId())).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);

        // Mock contentService for each episode
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(false)))
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

        // Mock repository save
        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Mock mapper
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    DiaryEntry entry = inv.getArgument(0);
                    return buildResponseDto(entry);
                });

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.now(), 3, null, null);

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        // Should return 3 episodes with fresh watchNumbers
        assertThat(result).hasSize(3);
        // Episodes should have incremented watchNumbers: 3 (2+1), 1 (0+1), 1 (0+1)
        DiaryEntryResponseDTO episodeWith2Watches = result.stream().filter(e -> e.watchNumber() == 3).findFirst().orElse(null);
        assertThat(episodeWith2Watches).isNotNull();
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Include The Auto-Generated Season Entry In The Result - When Bulk-Logging Completes The Season")
    void shouldIncludeTheAutoGeneratedSeasonEntryInTheResultWhenBulkLoggingCompletesTheSeason() {
        Content e1 = buildEpisode("900", 1, 1);
        Content e2 = buildEpisode("900", 1, 2);
        Content e3 = buildFinaleEpisode("900", 1, 3);
        Content season = buildSeason("900", 1);

        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(e3));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "900", 1))
                .thenReturn(
                        List.of(episodeWatchCount(1, 1L), episodeWatchCount(2, 0L), episodeWatchCount(3, 0L)),
                        List.of(episodeWatchCount(1, 1L), episodeWatchCount(2, 1L), episodeWatchCount(3, 0L)),
                        List.of(episodeWatchCount(1, 1L), episodeWatchCount(2, 1L), episodeWatchCount(3, 1L)));
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("900", ContentType.SEASON))
                .thenReturn(Optional.empty());
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubNewTransactionPassthrough();

        Answer<ContentRefDTO> contentResolutionAnswer = inv -> {
            ContentRefCreationDTO refDto = inv.getArgument(0);
            if (refDto.type() == ContentType.SEASON) {
                return new ContentRefDTO(season.getId(), null, ContentType.SEASON, "900", 1, null, null, null, null, null);
            }
            Content episode = switch (refDto.episodeNumber()) {
                case 1 -> e1;
                case 2 -> e2;
                default -> e3;
            };
            return new ContentRefDTO(episode.getId(), episode.getTmdbId(), episode.getType(), episode.getSeriesTmdbId(),
                    episode.getSeasonNumber(), episode.getEpisodeNumber(), episode.getIsSeasonFinale(),
                    episode.getIsSeriesFinale(), null, null);
        };
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class))).thenAnswer(contentResolutionAnswer);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(false))).thenAnswer(contentResolutionAnswer);

        when(contentRepository.getReferenceById(any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID id = inv.getArgument(0);
                    if (id.equals(e1.getId())) return e1;
                    if (id.equals(e2.getId())) return e2;
                    if (id.equals(e3.getId())) return e3;
                    if (id.equals(season.getId())) return season;
                    return null;
                });

        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    DiaryEntry entry = inv.getArgument(0);
                    Content entryContent = entry.getContent();
                    ContentRefDTO contentRef = new ContentRefDTO(entryContent.getId(), entryContent.getTmdbId(), entryContent.getType(),
                            entryContent.getSeriesTmdbId(), entryContent.getSeasonNumber(), entryContent.getEpisodeNumber(),
                            entryContent.getIsSeasonFinale(), entryContent.getIsSeriesFinale(), null, null);
                    return new DiaryEntryResponseDTO(entry.getId(), entry.getUser().getId(), contentRef, entry.getComment(),
                            entry.getScore(), entry.getWatchedDate(), entry.getWatchNumber(), entry.getWatchedInTheater(),
                            entry.getCustomPosterUrl(), entry.getAutoGenerated(), entry.getIgnore(), entry.getCreatedAt(), entry.getUpdatedAt(),
                            entry.getLikesCount(), false);
                });

        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "900", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.of(2024, 5, 1), null, null, null);

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        assertThat(result).hasSize(4);
        List<DiaryEntryResponseDTO> episodeEntries = result.stream()
                .filter(entry -> entry.content().type() == ContentType.EPISODE)
                .toList();
        assertThat(episodeEntries).hasSize(3);

        Optional<DiaryEntryResponseDTO> seasonEntry = result.stream()
                .filter(entry -> entry.content().type() == ContentType.SEASON)
                .findFirst();
        assertThat(seasonEntry).isPresent();
        assertThat(seasonEntry.get().autoGenerated()).isTrue();
        assertThat(seasonEntry.get().watchNumber()).isEqualTo(1);

        assertThat(episodeEntries).allMatch(DiaryEntryResponseDTO::ignore);
        assertThat(seasonEntry.get().ignore()).isFalse();
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Ignore Episode And Season Entries But Not The Completed Series - When Bulk-Logging A Complete SERIES")
    void shouldIgnoreEpisodeAndSeasonEntriesButNotTheCompletedSeriesWhenBulkLoggingACompleteSeries() {
        Content episode = buildFinaleEpisode("900", 1, 1);
        episode.setIsSeriesFinale(true);
        Content season = buildFinaleSeason("900", 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("900").type(ContentType.SERIES).build();

        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("900", ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("900", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(episode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "900", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L)));
        when(diaryEntryRepository.maxWatchNumberBySeasonInSeries(lucasId, "900"))
                .thenReturn(List.of(seasonWatchMax(1, 1)));
        when(diaryEntryRepository.findMaxWatchNumber(any(UUID.class), any(UUID.class))).thenReturn(0);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        stubNewTransactionPassthrough();

        Answer<ContentRefDTO> contentResolutionAnswer = inv -> {
            ContentRefCreationDTO refDto = inv.getArgument(0);
            if (refDto.type() == ContentType.EPISODE) {
                return new ContentRefDTO(episode.getId(), null, ContentType.EPISODE, "900", 1, 1, true, true, null, null);
            }
            if (refDto.type() == ContentType.SEASON) {
                return new ContentRefDTO(season.getId(), null, ContentType.SEASON, "900", 1, null, null, true, null, null);
            }
            return new ContentRefDTO(series.getId(), "900", ContentType.SERIES, null, null, null, null, null, null, null);
        };
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class))).thenAnswer(contentResolutionAnswer);
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class), eq(true))).thenAnswer(contentResolutionAnswer);

        when(contentRepository.getReferenceById(any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID id = inv.getArgument(0);
                    if (id.equals(episode.getId())) return episode;
                    if (id.equals(season.getId())) return season;
                    if (id.equals(series.getId())) return series;
                    return null;
                });

        when(diaryEntryRepository.saveAndFlush(any(DiaryEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    DiaryEntry entry = inv.getArgument(0);
                    Content entryContent = entry.getContent();
                    ContentRefDTO contentRef = new ContentRefDTO(entryContent.getId(), entryContent.getTmdbId(), entryContent.getType(),
                            entryContent.getSeriesTmdbId(), entryContent.getSeasonNumber(), entryContent.getEpisodeNumber(),
                            entryContent.getIsSeasonFinale(), entryContent.getIsSeriesFinale(), null, null);
                    return new DiaryEntryResponseDTO(entry.getId(), entry.getUser().getId(), contentRef, entry.getComment(),
                            entry.getScore(), entry.getWatchedDate(), entry.getWatchNumber(), entry.getWatchedInTheater(),
                            entry.getCustomPosterUrl(), entry.getAutoGenerated(), entry.getIgnore(), entry.getCreatedAt(), entry.getUpdatedAt(),
                            entry.getLikesCount(), false);
                });

        ContentRefCreationDTO seriesRef = new ContentRefCreationDTO("900", ContentType.SERIES, null, null, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seriesRef, LocalDate.now(), null, 1, null);

        List<DiaryEntryResponseDTO> result = diaryEntryService.createDiaryEntriesInBulk(lucasId, dto);

        assertThat(result).hasSize(3);
        DiaryEntryResponseDTO episodeEntry = result.stream().filter(e -> e.content().type() == ContentType.EPISODE).findFirst().orElseThrow();
        DiaryEntryResponseDTO seasonEntry = result.stream().filter(e -> e.content().type() == ContentType.SEASON).findFirst().orElseThrow();
        DiaryEntryResponseDTO seriesEntry = result.stream().filter(e -> e.content().type() == ContentType.SERIES).findFirst().orElseThrow();

        assertThat(episodeEntry.ignore()).isTrue();
        assertThat(seasonEntry.ignore()).isTrue();
        assertThat(seriesEntry.ignore()).isFalse();
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
        entry.setCustomPosterUrl("https://example.com/original.png");
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
        assertThat(saved.getCustomPosterUrl()).isEqualTo("https://example.com/original.png");
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Set AutoGenerated To False - When Editing An Auto-Generated Entry")
    void shouldSetAutoGeneratedToFalseWhenEditingAnAutoGeneratedEntry() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        entry.setAutoGenerated(true);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(diaryEntryMapper.diaryEntryToResponseDto(any(DiaryEntry.class), anyBoolean(), any())).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), minimalUpdateDto());

        assertThat(entry.getAutoGenerated()).isFalse();
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
        entry.setCustomPosterUrl("https://example.com/old.png");
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(entry);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false, List.of())).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), new DiaryEntryUpdateDTO(
                null, null, null, null, "https://example.com/new.png"));

        verify(diaryEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getCustomPosterUrl()).isEqualTo("https://example.com/new.png");
    }

    // ---------- deleteDiaryEntry ----------

    @Test
    @DisplayName("[deleteDiaryEntry] Should Delete Entry - When Owner Requests It")
    void shouldDeleteEntryWhenOwnerRequestsIt() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

        diaryEntryService.deleteDiaryEntry(lucasId, entry.getId(), false);

        verify(diaryEntryRepository).delete(entry);
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Throw NotFoundException - When Entry Does Not Exist")
    void shouldThrowNotFoundExceptionWhenEntryDoesNotExistOnDelete() {
        UUID missingId = UUID.randomUUID();
        when(diaryEntryRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> diaryEntryService.deleteDiaryEntry(lucasId, missingId, false))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Diary entry not found");

        verify(diaryEntryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Throw NotFoundException - When Entry Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenEntryBelongsToADifferentUserOnDelete() {
        DiaryEntry marinasEntry = buildEntry(marina, fightClub);
        when(diaryEntryRepository.findById(marinasEntry.getId())).thenReturn(Optional.of(marinasEntry));

        assertThatThrownBy(() -> diaryEntryService.deleteDiaryEntry(lucasId, marinasEntry.getId(), false))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Diary entry not found");

        verify(diaryEntryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Retract Auto-Generated Season Entry - When The Season Is No Longer Complete")
    void shouldRetractAutoGeneratedSeasonEntryWhenTheSeasonIsNoLongerComplete() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 2);
        Content season = buildSeason("1399", 1);
        DiaryEntry episodeEntry = buildEntry(lucas, finaleEpisode);
        DiaryEntry seasonEntry = buildEntry(lucas, season);
        seasonEntry.setAutoGenerated(true);

        when(diaryEntryRepository.findById(episodeEntry.getId())).thenReturn(Optional.of(episodeEntry));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L)));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 1, null, ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, season.getId(), 0))
                .thenReturn(List.of(seasonEntry));

        diaryEntryService.deleteDiaryEntry(lucasId, episodeEntry.getId(), false);

        verify(diaryEntryRepository).delete(episodeEntry);
        verify(diaryEntryRepository).deleteAll(List.of(seasonEntry));
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Not Retract Season Entry - When It Was Created Manually")
    void shouldNotRetractSeasonEntryWhenItWasCreatedManually() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 2);
        Content season = buildSeason("1399", 1);
        DiaryEntry episodeEntry = buildEntry(lucas, finaleEpisode);
        DiaryEntry seasonEntry = buildEntry(lucas, season);

        when(diaryEntryRepository.findById(episodeEntry.getId())).thenReturn(Optional.of(episodeEntry));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L)));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 1, null, ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, season.getId(), 0))
                .thenReturn(List.of(seasonEntry));

        diaryEntryService.deleteDiaryEntry(lucasId, episodeEntry.getId(), false);

        verify(diaryEntryRepository).delete(episodeEntry);
        verify(diaryEntryRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Not Retract Season Entry - When The Season Is Still Complete")
    void shouldNotRetractSeasonEntryWhenTheSeasonIsStillComplete() {
        Content episode = buildEpisode("1399", 1, 1);
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 2);
        Content season = buildSeason("1399", 1);
        DiaryEntry episodeEntry = buildEntry(lucas, episode);
        DiaryEntry seasonEntry = buildEntry(lucas, season);
        seasonEntry.setAutoGenerated(true);

        when(diaryEntryRepository.findById(episodeEntry.getId())).thenReturn(Optional.of(episodeEntry));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L), episodeWatchCount(2, 1L)));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 1, null, ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, season.getId(), 1))
                .thenReturn(List.of());

        diaryEntryService.deleteDiaryEntry(lucasId, episodeEntry.getId(), false);

        verify(diaryEntryRepository).delete(episodeEntry);
        verify(diaryEntryRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Cascade Retract Auto-Generated Series Entry - When Retracting The Last Auto-Generated Season")
    void shouldCascadeRetractAutoGeneratedSeriesEntryWhenRetractingTheLastAutoGeneratedSeason() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 1);
        Content season = buildSeason("1399", 1);
        Content finaleSeason = buildFinaleSeason("1399", 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES).build();
        DiaryEntry episodeEntry = buildEntry(lucas, finaleEpisode);
        DiaryEntry seasonEntry = buildEntry(lucas, season);
        seasonEntry.setAutoGenerated(true);
        DiaryEntry seriesEntry = buildEntry(lucas, series);
        seriesEntry.setAutoGenerated(true);

        when(diaryEntryRepository.findById(episodeEntry.getId())).thenReturn(Optional.of(episodeEntry));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of());
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 1, null, ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, season.getId(), 0))
                .thenReturn(List.of(seasonEntry));
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.of(finaleSeason));
        when(diaryEntryRepository.maxWatchNumberBySeasonInSeries(lucasId, "1399"))
                .thenReturn(List.of());
        when(contentRepository.findByTmdbIdAndType("1399", ContentType.SERIES)).thenReturn(Optional.of(series));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, series.getId(), 0))
                .thenReturn(List.of(seriesEntry));

        diaryEntryService.deleteDiaryEntry(lucasId, episodeEntry.getId(), false);

        verify(diaryEntryRepository).delete(episodeEntry);
        verify(diaryEntryRepository).deleteAll(List.of(seasonEntry));
        verify(diaryEntryRepository).deleteAll(List.of(seriesEntry));
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Not Retract Series Entry - When It Was Created Manually")
    void shouldNotRetractSeriesEntryWhenItWasCreatedManually() {
        Content finaleSeason = buildFinaleSeason("1399", 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES).build();
        DiaryEntry seasonEntry = buildEntry(lucas, finaleSeason);
        DiaryEntry seriesEntry = buildEntry(lucas, series);

        when(diaryEntryRepository.findById(seasonEntry.getId())).thenReturn(Optional.of(seasonEntry));
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.of(finaleSeason));
        when(diaryEntryRepository.maxWatchNumberBySeasonInSeries(lucasId, "1399"))
                .thenReturn(List.of());
        when(contentRepository.findByTmdbIdAndType("1399", ContentType.SERIES)).thenReturn(Optional.of(series));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, series.getId(), 0))
                .thenReturn(List.of(seriesEntry));

        diaryEntryService.deleteDiaryEntry(lucasId, seasonEntry.getId(), false);

        verify(diaryEntryRepository).delete(seasonEntry);
        verify(diaryEntryRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Not Retract Series Entry - When The Series Is Still Complete")
    void shouldNotRetractSeriesEntryWhenTheSeriesIsStillComplete() {
        Content season = buildSeason("1399", 1);
        Content finaleSeason = buildFinaleSeason("1399", 2);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("1399").type(ContentType.SERIES).build();
        DiaryEntry seasonEntry = buildEntry(lucas, season);
        DiaryEntry seriesEntry = buildEntry(lucas, series);
        seriesEntry.setAutoGenerated(true);

        when(diaryEntryRepository.findById(seasonEntry.getId())).thenReturn(Optional.of(seasonEntry));
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON))
                .thenReturn(Optional.of(finaleSeason));
        when(diaryEntryRepository.maxWatchNumberBySeasonInSeries(lucasId, "1399"))
                .thenReturn(List.of(seasonWatchMax(1, 1), seasonWatchMax(2, 1)));
        when(contentRepository.findByTmdbIdAndType("1399", ContentType.SERIES)).thenReturn(Optional.of(series));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, series.getId(), 1))
                .thenReturn(List.of());

        diaryEntryService.deleteDiaryEntry(lucasId, seasonEntry.getId(), false);

        verify(diaryEntryRepository).delete(seasonEntry);
        verify(diaryEntryRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Not Retract A Newer Complete Pass - When An Episode From An Older Pass Is Deleted")
    void shouldNotRetractANewerCompletePassWhenAnEpisodeFromAnOlderPassIsDeleted() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 1);
        Content season = buildSeason("1399", 1);
        DiaryEntry episodeEntry = buildEntry(lucas, finaleEpisode);
        episodeEntry.setWatchNumber(1);

        when(diaryEntryRepository.findById(episodeEntry.getId())).thenReturn(Optional.of(episodeEntry));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L)));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 1, null, ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, season.getId(), 1))
                .thenReturn(List.of());

        diaryEntryService.deleteDiaryEntry(lucasId, episodeEntry.getId(), false);

        verify(diaryEntryRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Retract Only The Pass That Lost Support - When Deleting The Episode That Sustained The Most Recent Pass")
    void shouldRetractOnlyThePassThatLostSupportWhenDeletingTheEpisodeThatSustainedTheMostRecentPass() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 1);
        Content season = buildSeason("1399", 1);
        DiaryEntry episodeEntry = buildEntry(lucas, finaleEpisode);
        episodeEntry.setWatchNumber(2);
        DiaryEntry seasonEntryWatchNumber2 = buildEntry(lucas, season);
        seasonEntryWatchNumber2.setWatchNumber(2);
        seasonEntryWatchNumber2.setAutoGenerated(true);

        when(diaryEntryRepository.findById(episodeEntry.getId())).thenReturn(Optional.of(episodeEntry));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L)));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 1, null, ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, season.getId(), 1))
                .thenReturn(List.of(seasonEntryWatchNumber2));

        diaryEntryService.deleteDiaryEntry(lucasId, episodeEntry.getId(), false);

        verify(diaryEntryRepository).deleteAll(List.of(seasonEntryWatchNumber2));
        verify(contentRepository).findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("1399", ContentType.SEASON);
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Preserve A Manually-Edited Entry Above The Threshold - When Retracting")
    void shouldPreserveAManuallyEditedEntryAboveTheThresholdWhenRetracting() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 1);
        Content season = buildSeason("1399", 1);
        DiaryEntry episodeEntry = buildEntry(lucas, finaleEpisode);
        episodeEntry.setWatchNumber(2);
        DiaryEntry seasonEntryWatchNumber2 = buildEntry(lucas, season);
        seasonEntryWatchNumber2.setWatchNumber(2);
        seasonEntryWatchNumber2.setAutoGenerated(false);

        when(diaryEntryRepository.findById(episodeEntry.getId())).thenReturn(Optional.of(episodeEntry));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L)));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 1, null, ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, season.getId(), 1))
                .thenReturn(List.of(seasonEntryWatchNumber2));

        diaryEntryService.deleteDiaryEntry(lucasId, episodeEntry.getId(), false);

        verify(diaryEntryRepository, never()).deleteAll(any());
        verify(contentRepository, never()).findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue(any(), any());
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Not Touch Season Or Series - When Deleting A Movie Entry")
    void shouldNotTouchSeasonOrSeriesWhenDeletingAMovieEntry() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

        diaryEntryService.deleteDiaryEntry(lucasId, entry.getId(), false);

        verify(diaryEntryRepository).delete(entry);
        verify(contentRepository, never())
                .findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(any(), any(), any());
        verify(contentRepository, never())
                .findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue(any(), any());
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Delete All Auto-Generated Episode Season And Series Entries - When A Series Entry Is Deleted Directly")
    void shouldDeleteAllAutoGeneratedEpisodeSeasonAndSeriesEntriesWhenASeriesEntryIsDeletedDirectly() {
        Content episode = buildEpisode("900", 1, 1);
        Content season = buildSeason("900", 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("900").type(ContentType.SERIES).build();
        DiaryEntry episodeEntry = buildEntry(lucas, episode);
        episodeEntry.setAutoGenerated(true);
        DiaryEntry seasonEntry = buildEntry(lucas, season);
        seasonEntry.setAutoGenerated(true);
        DiaryEntry seriesEntry = buildEntry(lucas, series);
        seriesEntry.setAutoGenerated(true);

        when(diaryEntryRepository.findById(seriesEntry.getId())).thenReturn(Optional.of(seriesEntry));
        when(diaryEntryRepository.findEpisodeEntriesInSeriesByWatchNumber(lucasId, "900", 1))
                .thenReturn(List.of(episodeEntry));
        when(diaryEntryRepository.findSeasonEntriesInSeriesByWatchNumber(lucasId, "900", 1))
                .thenReturn(List.of(seasonEntry));
        when(diaryEntryRepository.findSeriesEntriesByWatchNumber(lucasId, "900", 1))
                .thenReturn(List.of(seriesEntry));

        diaryEntryService.deleteDiaryEntry(lucasId, seriesEntry.getId(), false);

        verify(diaryEntryRepository).delete(seriesEntry);
        verify(diaryEntryRepository).deleteAll(List.of(episodeEntry, seasonEntry, seriesEntry));
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Preserve Manually-Edited Entries - When A Series Entry Is Deleted Directly")
    void shouldPreserveManuallyEditedEntriesWhenASeriesEntryIsDeletedDirectly() {
        Content episode = buildEpisode("900", 1, 1);
        Content season = buildSeason("900", 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("900").type(ContentType.SERIES).build();
        DiaryEntry episodeEntry = buildEntry(lucas, episode);
        episodeEntry.setAutoGenerated(false);
        DiaryEntry seasonEntry = buildEntry(lucas, season);
        seasonEntry.setAutoGenerated(false);
        DiaryEntry seriesEntry = buildEntry(lucas, series);
        seriesEntry.setAutoGenerated(false);

        when(diaryEntryRepository.findById(seriesEntry.getId())).thenReturn(Optional.of(seriesEntry));
        when(diaryEntryRepository.findEpisodeEntriesInSeriesByWatchNumber(lucasId, "900", 1))
                .thenReturn(List.of(episodeEntry));
        when(diaryEntryRepository.findSeasonEntriesInSeriesByWatchNumber(lucasId, "900", 1))
                .thenReturn(List.of(seasonEntry));
        when(diaryEntryRepository.findSeriesEntriesByWatchNumber(lucasId, "900", 1))
                .thenReturn(List.of(seriesEntry));

        diaryEntryService.deleteDiaryEntry(lucasId, seriesEntry.getId(), false);

        verify(diaryEntryRepository).delete(seriesEntry);
        verify(diaryEntryRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Delete A Manually-Edited Entry Above The Threshold - When OverrideProtectedEntries Is True")
    void shouldDeleteAManuallyEditedEntryAboveTheThresholdWhenOverrideProtectedEntriesIsTrue() {
        Content finaleEpisode = buildFinaleEpisode("1399", 1, 1);
        Content season = buildSeason("1399", 1);
        DiaryEntry episodeEntry = buildEntry(lucas, finaleEpisode);
        episodeEntry.setWatchNumber(2);
        DiaryEntry seasonEntryWatchNumber2 = buildEntry(lucas, season);
        seasonEntryWatchNumber2.setWatchNumber(2);
        seasonEntryWatchNumber2.setAutoGenerated(false);

        when(diaryEntryRepository.findById(episodeEntry.getId())).thenReturn(Optional.of(episodeEntry));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("1399", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "1399", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L)));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("1399", 1, null, ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, season.getId(), 1))
                .thenReturn(List.of(seasonEntryWatchNumber2));

        diaryEntryService.deleteDiaryEntry(lucasId, episodeEntry.getId(), true);

        verify(diaryEntryRepository).deleteAll(List.of(seasonEntryWatchNumber2));
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Delete Manually-Edited Series History - When Deleting A SERIES Entry Directly With OverrideProtectedEntries True")
    void shouldDeleteManuallyEditedSeriesHistoryWhenDeletingASeriesEntryDirectlyWithOverrideProtectedEntriesTrue() {
        Content episode = buildEpisode("900", 1, 1);
        Content season = buildSeason("900", 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("900").type(ContentType.SERIES).build();
        DiaryEntry episodeEntry = buildEntry(lucas, episode);
        episodeEntry.setAutoGenerated(false);
        DiaryEntry seasonEntry = buildEntry(lucas, season);
        seasonEntry.setAutoGenerated(false);
        DiaryEntry seriesEntry = buildEntry(lucas, series);
        seriesEntry.setAutoGenerated(false);

        when(diaryEntryRepository.findById(seriesEntry.getId())).thenReturn(Optional.of(seriesEntry));
        when(diaryEntryRepository.findEpisodeEntriesInSeriesByWatchNumber(lucasId, "900", 1))
                .thenReturn(List.of(episodeEntry));
        when(diaryEntryRepository.findSeasonEntriesInSeriesByWatchNumber(lucasId, "900", 1))
                .thenReturn(List.of(seasonEntry));
        when(diaryEntryRepository.findSeriesEntriesByWatchNumber(lucasId, "900", 1))
                .thenReturn(List.of(seriesEntry));

        diaryEntryService.deleteDiaryEntry(lucasId, seriesEntry.getId(), true);

        verify(diaryEntryRepository).delete(seriesEntry);
        verify(diaryEntryRepository).deleteAll(List.of(episodeEntry, seasonEntry, seriesEntry));
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Delete Only The Auto-Generated Entries Of A Mixed Series History - When A Series Entry Is Deleted Directly")
    void shouldDeleteOnlyTheAutoGeneratedEntriesOfAMixedSeriesHistoryWhenASeriesEntryIsDeletedDirectly() {
        Content episode = buildEpisode("900", 1, 1);
        Content season = buildSeason("900", 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("900").type(ContentType.SERIES).build();
        DiaryEntry autoGeneratedEpisodeEntry = buildEntry(lucas, episode);
        autoGeneratedEpisodeEntry.setAutoGenerated(true);
        DiaryEntry manuallyEditedSeasonEntry = buildEntry(lucas, season);
        manuallyEditedSeasonEntry.setAutoGenerated(false);
        DiaryEntry autoGeneratedSeriesEntry = buildEntry(lucas, series);
        autoGeneratedSeriesEntry.setAutoGenerated(true);
        DiaryEntry deletedSeriesEntry = buildEntry(lucas, series);

        when(diaryEntryRepository.findById(deletedSeriesEntry.getId())).thenReturn(Optional.of(deletedSeriesEntry));
        when(diaryEntryRepository.findEpisodeEntriesInSeriesByWatchNumber(lucasId, "900", 1))
                .thenReturn(List.of(autoGeneratedEpisodeEntry));
        when(diaryEntryRepository.findSeasonEntriesInSeriesByWatchNumber(lucasId, "900", 1))
                .thenReturn(List.of(manuallyEditedSeasonEntry));
        when(diaryEntryRepository.findSeriesEntriesByWatchNumber(lucasId, "900", 1))
                .thenReturn(List.of(autoGeneratedSeriesEntry));

        diaryEntryService.deleteDiaryEntry(lucasId, deletedSeriesEntry.getId(), false);

        verify(diaryEntryRepository).delete(deletedSeriesEntry);
        verify(diaryEntryRepository).deleteAll(List.of(autoGeneratedEpisodeEntry, autoGeneratedSeriesEntry));
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Only Wipe The Deleted Entry's Own Watch Cycle - When Other Rewatch Cycles Of The Same Series Exist")
    void shouldOnlyWipeTheDeletedEntrysOwnWatchCycleWhenOtherRewatchCyclesOfTheSameSeriesExist() {
        Content episode = buildEpisode("900", 1, 1);
        Content season = buildSeason("900", 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("900").type(ContentType.SERIES).build();

        DiaryEntry secondWatchEpisodeEntry = buildDiaryEntry(lucas, episode, 2);
        secondWatchEpisodeEntry.setAutoGenerated(true);
        DiaryEntry secondWatchSeasonEntry = buildDiaryEntry(lucas, season, 2);
        secondWatchSeasonEntry.setAutoGenerated(true);
        DiaryEntry secondWatchSeriesEntry = buildDiaryEntry(lucas, series, 2);
        secondWatchSeriesEntry.setAutoGenerated(true);

        when(diaryEntryRepository.findById(secondWatchSeriesEntry.getId())).thenReturn(Optional.of(secondWatchSeriesEntry));
        when(diaryEntryRepository.findEpisodeEntriesInSeriesByWatchNumber(lucasId, "900", 2))
                .thenReturn(List.of(secondWatchEpisodeEntry));
        when(diaryEntryRepository.findSeasonEntriesInSeriesByWatchNumber(lucasId, "900", 2))
                .thenReturn(List.of(secondWatchSeasonEntry));
        when(diaryEntryRepository.findSeriesEntriesByWatchNumber(lucasId, "900", 2))
                .thenReturn(List.of(secondWatchSeriesEntry));

        diaryEntryService.deleteDiaryEntry(lucasId, secondWatchSeriesEntry.getId(), false);

        verify(diaryEntryRepository).delete(secondWatchSeriesEntry);
        verify(diaryEntryRepository).deleteAll(List.of(secondWatchEpisodeEntry, secondWatchSeasonEntry, secondWatchSeriesEntry));
        verify(diaryEntryRepository, never()).findEpisodeEntriesInSeriesByWatchNumber(lucasId, "900", 1);
        verify(diaryEntryRepository, never()).findSeasonEntriesInSeriesByWatchNumber(lucasId, "900", 1);
        verify(diaryEntryRepository, never()).findSeriesEntriesByWatchNumber(lucasId, "900", 1);
    }

    @Test
    @DisplayName("[deleteAllDiaryEntriesForSeries] Should Delete Every Episode Season And Series Entry Across All Watch Numbers - When Called")
    void shouldDeleteEveryEpisodeSeasonAndSeriesEntryAcrossAllWatchNumbersWhenCalled() {
        Content episode = buildEpisode("900", 1, 1);
        Content season = buildSeason("900", 1);
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("900").type(ContentType.SERIES).build();

        DiaryEntry firstWatchEpisode = buildDiaryEntry(lucas, episode, 1);
        DiaryEntry secondWatchEpisode = buildDiaryEntry(lucas, episode, 2);
        DiaryEntry seasonEntry = buildDiaryEntry(lucas, season, 1);
        DiaryEntry seriesEntry = buildDiaryEntry(lucas, series, 1);

        when(diaryEntryRepository.findEpisodeEntriesBySeriesForUser(lucasId, "900"))
                .thenReturn(List.of(firstWatchEpisode, secondWatchEpisode));
        when(diaryEntryRepository.findAllSeasonEntriesInSeries(lucasId, "900"))
                .thenReturn(List.of(seasonEntry));
        when(diaryEntryRepository.findAllSeriesEntriesInSeries(lucasId, "900"))
                .thenReturn(List.of(seriesEntry));

        diaryEntryService.deleteAllDiaryEntriesForSeries(lucasId, "900");

        verify(diaryEntryRepository).deleteAll(List.of(firstWatchEpisode, secondWatchEpisode, seasonEntry, seriesEntry));
    }

    @Test
    @DisplayName("[deleteAllDiaryEntriesForSeries] Should Include Manually-Edited Entries - When Called")
    void shouldIncludeManuallyEditedEntriesWhenDeletingAllDiaryEntriesForSeries() {
        Content series = Content.builder().id(UUID.randomUUID()).tmdbId("900").type(ContentType.SERIES).build();
        DiaryEntry manuallyEditedEntry = buildDiaryEntry(lucas, series, 1);
        manuallyEditedEntry.setAutoGenerated(false);

        when(diaryEntryRepository.findEpisodeEntriesBySeriesForUser(lucasId, "900")).thenReturn(List.of());
        when(diaryEntryRepository.findAllSeasonEntriesInSeries(lucasId, "900")).thenReturn(List.of());
        when(diaryEntryRepository.findAllSeriesEntriesInSeries(lucasId, "900")).thenReturn(List.of(manuallyEditedEntry));

        diaryEntryService.deleteAllDiaryEntriesForSeries(lucasId, "900");

        verify(diaryEntryRepository).deleteAll(List.of(manuallyEditedEntry));
    }

    @Test
    @DisplayName("[deleteAllDiaryEntriesForSeries] Should Not Call DeleteAll - When No Entries Exist For That Series")
    void shouldNotCallDeleteAllWhenNoEntriesExistForThatSeries() {
        when(diaryEntryRepository.findEpisodeEntriesBySeriesForUser(lucasId, "900")).thenReturn(List.of());
        when(diaryEntryRepository.findAllSeasonEntriesInSeries(lucasId, "900")).thenReturn(List.of());
        when(diaryEntryRepository.findAllSeriesEntriesInSeries(lucasId, "900")).thenReturn(List.of());

        diaryEntryService.deleteAllDiaryEntriesForSeries(lucasId, "900");

        verify(diaryEntryRepository, never()).deleteAll(any());
    }

    // ---------- helpers ----------

    private void stubEmptyDiaryPage() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(any(), any(PageRequest.class)))
                .thenReturn(Page.empty());
    }

    private void stubContentResolution(Content content) {
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenReturn(new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(), null, null, null,
                        null, null, LocalDateTime.now(), LocalDateTime.now()));
    }

    private void stubNewTransactionPassthrough() {
        when(newTransactionExecutor.runInNewTransaction(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    private record CompletionSignalResult(DiaryEntry completedSeason, DiaryEntry completedSeries) {
    }

    private CompletionSignalResult invokeTriggerCompletionCascade(UUID userId, Content loggedContent, LocalDate watchedDate) throws Exception {
        return invokeTriggerCompletionCascade(userId, loggedContent, watchedDate, ContentType.EPISODE);
    }

    private CompletionSignalResult invokeTriggerCompletionCascade(UUID userId, Content loggedContent, LocalDate watchedDate,
            ContentType requestedType) throws Exception {
        Method cascadeMethod = DiaryEntryServiceImpl.class.getDeclaredMethod(
                "triggerCompletionCascade", UUID.class, Content.class, LocalDate.class, ContentType.class);
        cascadeMethod.setAccessible(true);
        Object signal = cascadeMethod.invoke(diaryEntryService, userId, loggedContent, watchedDate, requestedType);

        Class<?> signalClass = Class.forName(DiaryEntryServiceImpl.class.getName() + "$CompletionSignal");
        Method completedSeasonMethod = signalClass.getDeclaredMethod("completedSeason");
        Method completedSeriesMethod = signalClass.getDeclaredMethod("completedSeries");
        completedSeasonMethod.setAccessible(true);
        completedSeriesMethod.setAccessible(true);

        return new CompletionSignalResult(
                (DiaryEntry) completedSeasonMethod.invoke(signal),
                (DiaryEntry) completedSeriesMethod.invoke(signal));
    }

    private DiaryEntryRepository.EpisodeWatchCount episodeWatchCount(int episodeNumber, long count) {
        return new EpisodeCount(episodeNumber, count);
    }

    private DiaryEntryRepository.SeasonWatchMax seasonWatchMax(int seasonNumber, int maxWatchNumber) {
        return new SeasonMax(seasonNumber, maxWatchNumber);
    }

    private record EpisodeCount(Integer episodeNumber, Long count) implements DiaryEntryRepository.EpisodeWatchCount {
        @Override
        public Integer getEpisodeNumber() {
            return episodeNumber;
        }

        @Override
        public Long getCount() {
            return count;
        }
    }

    private record SeasonMax(Integer seasonNumber, Integer maxWatchNumber) implements DiaryEntryRepository.SeasonWatchMax {
        @Override
        public Integer getSeasonNumber() {
            return seasonNumber;
        }

        @Override
        public Integer getMaxWatchNumber() {
            return maxWatchNumber;
        }
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
                entry.getCustomPosterUrl(), entry.getAutoGenerated(), entry.getIgnore(), entry.getCreatedAt(), entry.getUpdatedAt(),
                entry.getLikesCount(), false);
    }

    private Content buildContent(String tmdbId, ContentType type) {
        LocalDateTime now = LocalDateTime.now();
        return Content.builder()
                .id(UUID.randomUUID())
                .tmdbId(tmdbId)
                .type(type)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Content buildEpisode(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        return Content.builder()
                .id(UUID.randomUUID())
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .type(ContentType.EPISODE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private Content buildFinaleEpisode(String seriesTmdbId, Integer seasonNumber, Integer episodeNumber) {
        Content episode = buildEpisode(seriesTmdbId, seasonNumber, episodeNumber);
        episode.setIsSeasonFinale(true);
        return episode;
    }

    private Content buildSeason(String seriesTmdbId, Integer seasonNumber) {
        return Content.builder()
                .id(UUID.randomUUID())
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .type(ContentType.SEASON)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private Content buildFinaleSeason(String seriesTmdbId, Integer seasonNumber) {
        Content season = buildSeason(seriesTmdbId, seasonNumber);
        season.setIsSeriesFinale(true);
        return season;
    }

    @Test
    @DisplayName("[computeDeletionImpact] Should Throw NotFoundException - When Entry Does Not Exist")
    void shouldThrowNotFoundExceptionWhenEntryDoesNotExistOnComputeDeletionImpact() {
        UUID missingId = UUID.randomUUID();
        when(diaryEntryRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> diaryEntryService.computeDeletionImpact(lucasId, missingId, false))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Diary entry not found");

        verify(diaryEntryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[computeDeletionImpact] Should Throw NotFoundException - When Entry Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenEntryBelongsToADifferentUserOnComputeDeletionImpact() {
        DiaryEntry marinasEntry = buildEntry(marina, fightClub);
        when(diaryEntryRepository.findById(marinasEntry.getId())).thenReturn(Optional.of(marinasEntry));

        assertThatThrownBy(() -> diaryEntryService.computeDeletionImpact(lucasId, marinasEntry.getId(), false))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Diary entry not found");

        verify(diaryEntryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[computeDeletionImpact] Should Delete And Flush But Return Empty - When Content Type Is Movie")
    void shouldDeleteAndFlushButReturnEmptyWhenContentTypeIsMovie() {
        DiaryEntry entry = buildDiaryEntry(lucas, fightClub, 1);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

        DeletionImpactDTO result = diaryEntryService.computeDeletionImpact(lucasId, entry.getId(), false);

        assertThat(result.wouldDelete()).isEmpty();
        verify(diaryEntryRepository).delete(entry);
        verify(diaryEntryRepository).flush();
        verify(contentRepository, never())
                .findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue(any(), any(), any());
    }

    @Test
    @DisplayName("[computeDeletionImpact] Should Delete And Flush Before Querying Season Candidates - When Content Type Is Episode")
    void shouldDeleteAndFlushBeforeQueryingSeasonCandidatesWhenContentTypeIsEpisode() {
        Content episodeContent = buildEpisode("tt1", 1, 1);
        DiaryEntry entry = buildDiaryEntry(lucas, episodeContent, 1);

        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("tt1", 1, ContentType.EPISODE))
                .thenReturn(Optional.empty());

        DeletionImpactDTO result = diaryEntryService.computeDeletionImpact(lucasId, entry.getId(), false);

        assertThat(result.wouldDelete()).isEmpty();

        InOrder inOrder = inOrder(diaryEntryRepository, contentRepository);
        inOrder.verify(diaryEntryRepository).delete(entry);
        inOrder.verify(diaryEntryRepository).flush();
        inOrder.verify(contentRepository)
                .findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("tt1", 1, ContentType.EPISODE);
    }

    @Test
    @DisplayName("[computeDeletionImpact] Should Delete And Flush Before Querying Series Candidates - When Content Type Is Season")
    void shouldDeleteAndFlushBeforeQueryingSeriesCandidatesWhenContentTypeIsSeason() {
        Content finaleSeason = buildFinaleSeason("tt3", 1);
        DiaryEntry seasonEntry = buildDiaryEntry(lucas, finaleSeason, 1);

        when(diaryEntryRepository.findById(seasonEntry.getId())).thenReturn(Optional.of(seasonEntry));
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("tt3", ContentType.SEASON))
                .thenReturn(Optional.empty());

        DeletionImpactDTO result = diaryEntryService.computeDeletionImpact(lucasId, seasonEntry.getId(), false);

        assertThat(result.wouldDelete()).isEmpty();

        InOrder inOrder = inOrder(diaryEntryRepository, contentRepository);
        inOrder.verify(diaryEntryRepository).delete(seasonEntry);
        inOrder.verify(diaryEntryRepository).flush();
        inOrder.verify(contentRepository)
                .findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("tt3", ContentType.SEASON);
    }

    @Test
    @DisplayName("[computeDeletionImpact] Should Return Empty WouldDelete - When Deleting An Episode Entry That Is Not The Bottleneck")
    void shouldReturnEmptyWouldDeleteWhenDeletingAnEpisodeEntryThatIsNotTheBottleneck() {
        Content finaleEpisode = buildFinaleEpisode("tt2", 1, 2);
        Content nonFinaleEpisode = buildEpisode("tt2", 1, 1);
        Content season = buildSeason("tt2", 1);
        DiaryEntry entry = buildDiaryEntry(lucas, nonFinaleEpisode, 2);
        DiaryEntry staleSeasonCandidate = buildDiaryEntry(lucas, season, 1);
        staleSeasonCandidate.setAutoGenerated(true);

        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("tt2", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "tt2", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L), episodeWatchCount(2, 1L)));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("tt2", 1, null, ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, season.getId(), 1))
                .thenReturn(List.of());
        lenient().when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, season.getId(), 0))
                .thenReturn(List.of(staleSeasonCandidate));

        DeletionImpactDTO result = diaryEntryService.computeDeletionImpact(lucasId, entry.getId(), false);

        assertThat(result.wouldDelete()).isEmpty();
        verify(diaryEntryRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("[computeDeletionImpact] Should Include Season Candidate - When Deleting The Bottleneck Episode Entry")
    void shouldIncludeSeasonCandidateWhenDeletingTheBottleneckEpisodeEntry() {
        Content finaleEpisode = buildFinaleEpisode("tt2", 1, 2);
        Content season = buildSeason("tt2", 1);
        DiaryEntry entry = buildDiaryEntry(lucas, finaleEpisode, 1);
        DiaryEntry seasonEntry = buildDiaryEntry(lucas, season, 1);
        seasonEntry.setAutoGenerated(true);

        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("tt2", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "tt2", 1))
                .thenReturn(List.of(episodeWatchCount(1, 1L), episodeWatchCount(2, 0L)));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("tt2", 1, null, ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, season.getId(), 0))
                .thenReturn(List.of(seasonEntry));
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("tt2", ContentType.SEASON))
                .thenReturn(Optional.empty());

        DeletionImpactDTO result = diaryEntryService.computeDeletionImpact(lucasId, entry.getId(), false);

        assertThat(result.wouldDelete())
                .containsExactly(new DeletionImpactItemDTO(seasonEntry.getId(), ContentType.SEASON,
                        seasonEntry.getWatchedDate(), seasonEntry.getWatchNumber(), true, false));
        verify(diaryEntryRepository).deleteAll(List.of(seasonEntry));
    }

    @Test
    @DisplayName("[computeDeletionImpact] Should Include Season And Series Candidates - When Deleting An Episode That Sustains Both")
    void shouldIncludeSeasonAndSeriesCandidatesWhenDeletingAnEpisodeThatSustainsBoth() {
        Content seasonTwoFinaleEpisode = buildFinaleEpisode("tt1", 2, 1);
        seasonTwoFinaleEpisode.setIsSeriesFinale(true);
        Content seasonTwoContent = buildFinaleSeason("tt1", 2);
        Content seriesContent = buildContent("tt1", ContentType.SERIES);

        DiaryEntry episodeEntry = buildDiaryEntry(lucas, seasonTwoFinaleEpisode, 1);
        DiaryEntry seasonTwoEntry = buildDiaryEntry(lucas, seasonTwoContent, 1);
        seasonTwoEntry.setAutoGenerated(true);
        DiaryEntry seriesEntry = buildDiaryEntry(lucas, seriesContent, 1);
        seriesEntry.setAutoGenerated(true);

        List<DiaryEntry> alreadyDeleted = new ArrayList<>();
        doAnswer(invocation -> {
            Iterable<DiaryEntry> deleted = invocation.getArgument(0);
            deleted.forEach(alreadyDeleted::add);
            return null;
        }).when(diaryEntryRepository).deleteAll(any());

        when(diaryEntryRepository.findById(episodeEntry.getId())).thenReturn(Optional.of(episodeEntry));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("tt1", 2, ContentType.EPISODE))
                .thenReturn(Optional.of(seasonTwoFinaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "tt1", 2))
                .thenReturn(List.of());
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("tt1", 2, null, ContentType.SEASON))
                .thenReturn(Optional.of(seasonTwoContent));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, seasonTwoContent.getId(), 0))
                .thenReturn(List.of(seasonTwoEntry));
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("tt1", ContentType.SEASON))
                .thenReturn(Optional.of(seasonTwoContent));
        when(diaryEntryRepository.maxWatchNumberBySeasonInSeries(lucasId, "tt1"))
                .thenAnswer(invocation -> alreadyDeleted.contains(seasonTwoEntry)
                        ? List.of(seasonWatchMax(1, 1))
                        : List.of(seasonWatchMax(1, 1), seasonWatchMax(2, 1)));
        when(contentRepository.findByTmdbIdAndType("tt1", ContentType.SERIES))
                .thenReturn(Optional.of(seriesContent));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, seriesContent.getId(), 0))
                .thenReturn(List.of(seriesEntry));

        DeletionImpactDTO result = diaryEntryService.computeDeletionImpact(lucasId, episodeEntry.getId(), false);

        assertThat(result.wouldDelete())
                .containsExactly(
                        new DeletionImpactItemDTO(seasonTwoEntry.getId(), ContentType.SEASON,
                                seasonTwoEntry.getWatchedDate(), 1, true, false),
                        new DeletionImpactItemDTO(seriesEntry.getId(), ContentType.SERIES,
                                seriesEntry.getWatchedDate(), 1, true, false));
    }

    @Test
    @DisplayName("[computeDeletionImpact] Should Return Empty WouldDelete - When The Only Candidate Is A Manually-Edited Entry And OverrideProtectedEntries Is False")
    void shouldReturnEmptyWouldDeleteWhenTheOnlyCandidateIsAManuallyEditedEntryAndOverrideProtectedEntriesIsFalse() {
        Content finaleEpisode = buildFinaleEpisode("tt4", 1, 1);
        Content season = buildSeason("tt4", 1);
        DiaryEntry entry = buildDiaryEntry(lucas, finaleEpisode, 1);
        DiaryEntry manuallyEditedSeasonEntry = buildDiaryEntry(lucas, season, 1);

        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("tt4", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "tt4", 1))
                .thenReturn(List.of());
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("tt4", 1, null, ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, season.getId(), 0))
                .thenReturn(List.of(manuallyEditedSeasonEntry));

        DeletionImpactDTO result = diaryEntryService.computeDeletionImpact(lucasId, entry.getId(), false);

        assertThat(result.wouldDelete()).isEmpty();
        verify(diaryEntryRepository, never()).deleteAll(any());
        verify(contentRepository, never()).findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue(any(), any());
    }

    @Test
    @DisplayName("[computeDeletionImpact] Should Include The Manually-Edited Entry With AutoGenerated False - When OverrideProtectedEntries Is True")
    void shouldIncludeTheManuallyEditedEntryWithAutoGeneratedFalseWhenOverrideProtectedEntriesIsTrue() {
        Content finaleEpisode = buildFinaleEpisode("tt4", 1, 1);
        Content season = buildSeason("tt4", 1);
        DiaryEntry entry = buildDiaryEntry(lucas, finaleEpisode, 1);
        DiaryEntry manuallyEditedSeasonEntry = buildDiaryEntry(lucas, season, 1);
        manuallyEditedSeasonEntry.setComment("Great season!");

        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndTypeAndIsSeasonFinaleTrue("tt4", 1, ContentType.EPISODE))
                .thenReturn(Optional.of(finaleEpisode));
        when(diaryEntryRepository.countEntriesByEpisodeNumberInSeason(lucasId, "tt4", 1))
                .thenReturn(List.of());
        when(contentRepository.findBySeriesTmdbIdAndSeasonNumberAndEpisodeNumberAndType("tt4", 1, null, ContentType.SEASON))
                .thenReturn(Optional.of(season));
        when(diaryEntryRepository.findByUserIdAndContentIdAndWatchNumberGreaterThan(lucasId, season.getId(), 0))
                .thenReturn(List.of(manuallyEditedSeasonEntry));
        when(contentRepository.findBySeriesTmdbIdAndTypeAndIsSeriesFinaleTrue("tt4", ContentType.SEASON))
                .thenReturn(Optional.empty());

        DeletionImpactDTO result = diaryEntryService.computeDeletionImpact(lucasId, entry.getId(), true);

        assertThat(result.wouldDelete())
                .containsExactly(new DeletionImpactItemDTO(manuallyEditedSeasonEntry.getId(), ContentType.SEASON,
                        manuallyEditedSeasonEntry.getWatchedDate(), 1, false, true));
    }

    @Test
    @DisplayName("[computeDeletionImpact] Should Mark HasReview True Independently Of AutoGenerated - When The Candidate Entry Has A Comment Or A Score")
    void shouldMarkHasReviewTrueIndependentlyOfAutoGeneratedWhenTheCandidateEntryHasACommentOrAScore() {
        Content episodeContent = buildEpisode("tt1", 1, 1);
        DiaryEntry episodeWithComment = buildDiaryEntry(lucas, episodeContent, 1);
        episodeWithComment.setComment("Great episode");

        Content seasonContent = buildSeason("tt1", 1);
        DiaryEntry seasonWithScore = buildDiaryEntry(lucas, seasonContent, 1);
        seasonWithScore.setScore(9);

        Content seriesContent = buildContent("tt1", ContentType.SERIES);
        DiaryEntry seriesWithNeither = buildDiaryEntry(lucas, seriesContent, 1);
        seriesWithNeither.setAutoGenerated(true);

        DiaryEntry queryEntry = buildDiaryEntry(lucas, seriesContent, 1);

        when(diaryEntryRepository.findById(queryEntry.getId())).thenReturn(Optional.of(queryEntry));
        when(diaryEntryRepository.findEpisodeEntriesInSeriesByWatchNumber(lucasId, "tt1", 1))
                .thenReturn(List.of(episodeWithComment));
        when(diaryEntryRepository.findSeasonEntriesInSeriesByWatchNumber(lucasId, "tt1", 1))
                .thenReturn(List.of(seasonWithScore));
        when(diaryEntryRepository.findSeriesEntriesByWatchNumber(lucasId, "tt1", 1))
                .thenReturn(List.of(seriesWithNeither));

        DeletionImpactDTO result = diaryEntryService.computeDeletionImpact(lucasId, queryEntry.getId(), true);

        assertThat(result.wouldDelete()).containsExactly(
                new DeletionImpactItemDTO(episodeWithComment.getId(), ContentType.EPISODE,
                        episodeWithComment.getWatchedDate(), 1, false, true),
                new DeletionImpactItemDTO(seasonWithScore.getId(), ContentType.SEASON,
                        seasonWithScore.getWatchedDate(), 1, false, true),
                new DeletionImpactItemDTO(seriesWithNeither.getId(), ContentType.SERIES,
                        seriesWithNeither.getWatchedDate(), 1, true, false));

        InOrder inOrder = inOrder(diaryEntryRepository);
        inOrder.verify(diaryEntryRepository).delete(queryEntry);
        inOrder.verify(diaryEntryRepository).flush();
        inOrder.verify(diaryEntryRepository).findEpisodeEntriesInSeriesByWatchNumber(lucasId, "tt1", 1);
    }
}
