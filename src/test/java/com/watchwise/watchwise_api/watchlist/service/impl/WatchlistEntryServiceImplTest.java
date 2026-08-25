package com.watchwise.watchwise_api.watchlist.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryCreationDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryReorderDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryResponseDTO;
import com.watchwise.watchwise_api.watchlist.entity.WatchlistEntry;
import com.watchwise.watchwise_api.watchlist.mapper.WatchlistEntryMapper;
import com.watchwise.watchwise_api.watchlist.repository.WatchlistEntryRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WatchlistEntryServiceImplTest {

    @Mock
    private WatchlistEntryRepository watchlistEntryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentService contentService;

    @Mock
    private FollowerRepository followerRepository;

    @Mock
    private WatchlistEntryMapper watchlistEntryMapper;

    @InjectMocks
    private WatchlistEntryServiceImpl watchlistEntryService;

    @Captor
    private ArgumentCaptor<WatchlistEntry> entryCaptor;

    @Captor
    private ArgumentCaptor<PageRequest> pageRequestCaptor;

    @Captor
    private ArgumentCaptor<ContentRefCreationDTO> contentRefCreationCaptor;

    private UUID lucasId;
    private UUID marinaId;
    private User lucas;
    private User marina;
    private Content fightClub;
    private Content pulpFiction;
    private Content breakingBad;

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
        pulpFiction = buildContent("680", ContentType.MOVIE);
        breakingBad = buildContent("1396", ContentType.SERIES);
    }

    // ---------- getWatchlist ----------

    @Test
    @DisplayName("[getWatchlist] Should Return Mapped Page - When Viewer Is The Profile Owner")
    void shouldReturnMappedPageWhenViewerIsTheProfileOwner() {
        WatchlistEntry entry = buildEntry(lucas, fightClub, ContentType.MOVIE, 1);
        WatchlistEntryResponseDTO dto = buildResponseDto(entry);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(eq(lucasId), eq(ContentType.MOVIE), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(entry)));
        when(watchlistEntryMapper.watchlistEntryToResponseDto(entry)).thenReturn(dto);

        Page<WatchlistEntryResponseDTO> result = watchlistEntryService.getWatchlist(lucasId, lucasId, ContentType.MOVIE, 1, 10);

        assertThat(result.getContent()).containsExactly(dto);
    }

    @Test
    @DisplayName("[getWatchlist] Should Return Empty Page - When User Has No Entries Of That Type")
    void shouldReturnEmptyPageWhenUserHasNoEntriesOfThatType() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(eq(lucasId), eq(ContentType.MOVIE), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<WatchlistEntryResponseDTO> result = watchlistEntryService.getWatchlist(lucasId, lucasId, ContentType.MOVIE, 1, 10);

        assertThat(result.getContent()).isEmpty();
        verify(watchlistEntryMapper, never()).watchlistEntryToResponseDto(any());
    }

    @Test
    @DisplayName("[getWatchlist] Should Throw NotFoundException - When User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenUserDoesNotExist() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> watchlistEntryService.getWatchlist(lucasId, lucasId, ContentType.MOVIE, 1, 10))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verifyNoInteractions(watchlistEntryRepository);
    }

    @Test
    @DisplayName("[getWatchlist] Should Throw BadRequestException - When Type Is Season")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonOnGet() {
        assertThatThrownBy(() -> watchlistEntryService.getWatchlist(lucasId, lucasId, ContentType.SEASON, 1, 10))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(userRepository, watchlistEntryRepository);
    }

    @Test
    @DisplayName("[getWatchlist] Should Throw BadRequestException - When Type Is Episode")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeOnGet() {
        assertThatThrownBy(() -> watchlistEntryService.getWatchlist(lucasId, lucasId, ContentType.EPISODE, 1, 10))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(userRepository, watchlistEntryRepository);
    }

    @Test
    @DisplayName("[getWatchlist] Should Return Entries - When Target Profile Is Public And Viewer Is A Different User")
    void shouldReturnEntriesWhenTargetProfileIsPublicAndViewerIsADifferentUser() {
        lucas.setIsProfilePublic(true);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(eq(lucasId), eq(ContentType.MOVIE), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<WatchlistEntryResponseDTO> result = watchlistEntryService.getWatchlist(marinaId, lucasId, ContentType.MOVIE, 1, 10);

        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(followerRepository);
    }

    @Test
    @DisplayName("[getWatchlist] Should Return Entries - When Target Profile Is Private And Viewer Is An Accepted Follower")
    void shouldReturnEntriesWhenTargetProfileIsPrivateAndViewerIsAnAcceptedFollower() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(eq(lucasId), eq(ContentType.MOVIE), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<WatchlistEntryResponseDTO> result = watchlistEntryService.getWatchlist(marinaId, lucasId, ContentType.MOVIE, 1, 10);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[getWatchlist] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateAndViewerIsNotAnAcceptedFollower() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> watchlistEntryService.getWatchlist(marinaId, lucasId, ContentType.MOVIE, 1, 10))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");

        verifyNoInteractions(watchlistEntryRepository);
    }

    @Test
    @DisplayName("[getWatchlist] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPageWhenPageNumberIsNull() {
        stubEmptyWatchlistPage();

        watchlistEntryService.getWatchlist(lucasId, lucasId, ContentType.MOVIE, null, 10);

        verify(watchlistEntryRepository).findByUserIdAndTypeOrderByPositionAsc(eq(lucasId), eq(ContentType.MOVIE), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(WatchlistEntryServiceImpl.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getWatchlist] Should Use Default Page - When Page Number Is Zero")
    void shouldUseDefaultPageWhenPageNumberIsZero() {
        stubEmptyWatchlistPage();

        watchlistEntryService.getWatchlist(lucasId, lucasId, ContentType.MOVIE, 0, 10);

        verify(watchlistEntryRepository).findByUserIdAndTypeOrderByPositionAsc(eq(lucasId), eq(ContentType.MOVIE), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(WatchlistEntryServiceImpl.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getWatchlist] Should Use Page Number Minus One - When Page Number Is Positive")
    void shouldUsePageNumberMinusOneWhenPageNumberIsPositive() {
        stubEmptyWatchlistPage();

        watchlistEntryService.getWatchlist(lucasId, lucasId, ContentType.MOVIE, 3, 10);

        verify(watchlistEntryRepository).findByUserIdAndTypeOrderByPositionAsc(eq(lucasId), eq(ContentType.MOVIE), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("[getWatchlist] Should Throw BadRequestException - When Page Number Is Negative")
    void shouldThrowBadRequestExceptionWhenPageNumberIsNegative() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> watchlistEntryService.getWatchlist(lucasId, lucasId, ContentType.MOVIE, -1, 10))
                .isInstanceOf(BadRequestException.class);

        verify(watchlistEntryRepository, never()).findByUserIdAndTypeOrderByPositionAsc(any(), any(), any());
    }

    @Test
    @DisplayName("[getWatchlist] Should Use Default Page Size - When Page Size Is Null")
    void shouldUseDefaultPageSizeWhenPageSizeIsNull() {
        stubEmptyWatchlistPage();

        watchlistEntryService.getWatchlist(lucasId, lucasId, ContentType.MOVIE, 1, null);

        verify(watchlistEntryRepository).findByUserIdAndTypeOrderByPositionAsc(eq(lucasId), eq(ContentType.MOVIE), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(WatchlistEntryServiceImpl.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getWatchlist] Should Clamp Page Size To Max Limit - When Page Size Exceeds Limit")
    void shouldClampPageSizeToMaxLimitWhenPageSizeExceedsLimit() {
        stubEmptyWatchlistPage();

        watchlistEntryService.getWatchlist(lucasId, lucasId, ContentType.MOVIE, 1, 1001);

        verify(watchlistEntryRepository).findByUserIdAndTypeOrderByPositionAsc(eq(lucasId), eq(ContentType.MOVIE), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(WatchlistEntryServiceImpl.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getWatchlist] Should Use Provided Page Size - When Page Size Is Valid")
    void shouldUseProvidedPageSizeWhenPageSizeIsValid() {
        stubEmptyWatchlistPage();

        watchlistEntryService.getWatchlist(lucasId, lucasId, ContentType.MOVIE, 1, 25);

        verify(watchlistEntryRepository).findByUserIdAndTypeOrderByPositionAsc(eq(lucasId), eq(ContentType.MOVIE), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("[getWatchlist] Should Use Provided Page Size - When Page Size Is At Max Limit")
    void shouldUseProvidedPageSizeWhenPageSizeIsAtMaxLimit() {
        stubEmptyWatchlistPage();

        watchlistEntryService.getWatchlist(lucasId, lucasId, ContentType.MOVIE, 1, 1000);

        verify(watchlistEntryRepository).findByUserIdAndTypeOrderByPositionAsc(eq(lucasId), eq(ContentType.MOVIE), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(1000);
    }

    @Test
    @DisplayName("[getWatchlist] Should Throw BadRequestException - When Page Size Is Negative")
    void shouldThrowBadRequestExceptionWhenPageSizeIsNegative() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> watchlistEntryService.getWatchlist(lucasId, lucasId, ContentType.MOVIE, 1, -5))
                .isInstanceOf(BadRequestException.class);

        verify(watchlistEntryRepository, never()).findByUserIdAndTypeOrderByPositionAsc(any(), any(), any());
    }

    @Test
    @DisplayName("[getWatchlist] Should Throw BadRequestException - When Page Size Is Zero")
    void shouldThrowBadRequestExceptionWhenPageSizeIsZero() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> watchlistEntryService.getWatchlist(lucasId, lucasId, ContentType.MOVIE, 1, 0))
                .isInstanceOf(BadRequestException.class);

        verify(watchlistEntryRepository, never()).findByUserIdAndTypeOrderByPositionAsc(any(), any(), any());
    }

    // ---------- insertEntry ----------

    @Test
    @DisplayName("[insertEntry] Should Insert At Position One - When Watchlist Is Empty")
    void shouldInsertAtPositionOneWhenWatchlistIsEmpty() {
        WatchlistEntry savedEntry = buildEntry(lucas, fightClub, ContentType.MOVIE, 1);
        WatchlistEntryResponseDTO expectedDto = buildResponseDto(savedEntry);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(watchlistEntryRepository.countByUserIdAndType(lucasId, ContentType.MOVIE)).thenReturn(0L);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(watchlistEntryRepository.save(any(WatchlistEntry.class))).thenReturn(savedEntry);
        when(watchlistEntryMapper.watchlistEntryToResponseDto(savedEntry)).thenReturn(expectedDto);

        WatchlistEntryResponseDTO result = watchlistEntryService.insertEntry(
                lucasId, ContentType.MOVIE, new WatchlistEntryCreationDTO(fightClub.getTmdbId()));

        assertThat(result).isEqualTo(expectedDto);
        verify(watchlistEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getPosition()).isEqualTo(1);
        verify(watchlistEntryRepository, times(1)).flush();
        verify(contentService).getOrCreateReference(contentRefCreationCaptor.capture());
        assertThat(contentRefCreationCaptor.getValue())
                .isEqualTo(new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null));
    }

    @Test
    @DisplayName("[insertEntry] Should Insert At Next Free Position - When Watchlist Already Has Entries")
    void shouldInsertAtNextFreePositionWhenWatchlistAlreadyHasEntries() {
        Content thirdMovie = buildContent("111", ContentType.MOVIE);
        WatchlistEntry savedEntry = buildEntry(lucas, thirdMovie, ContentType.MOVIE, 3);
        stubContentResolution(thirdMovie, ContentType.MOVIE);
        when(watchlistEntryRepository.countByUserIdAndType(lucasId, ContentType.MOVIE)).thenReturn(2L);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(thirdMovie.getId())).thenReturn(thirdMovie);
        when(watchlistEntryRepository.save(any(WatchlistEntry.class))).thenReturn(savedEntry);
        when(watchlistEntryMapper.watchlistEntryToResponseDto(savedEntry)).thenReturn(buildResponseDto(savedEntry));

        watchlistEntryService.insertEntry(
                lucasId, ContentType.MOVIE, new WatchlistEntryCreationDTO(thirdMovie.getTmdbId()));

        verify(watchlistEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getPosition()).isEqualTo(3);
        verify(watchlistEntryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[insertEntry] Should Throw BadRequestException - When Type Is Season")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonOnInsert() {
        assertThatThrownBy(() -> watchlistEntryService.insertEntry(
                lucasId, ContentType.SEASON, new WatchlistEntryCreationDTO(fightClub.getTmdbId())))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(watchlistEntryRepository, contentService, userRepository, contentRepository);
    }

    @Test
    @DisplayName("[insertEntry] Should Throw BadRequestException - When Type Is Episode")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeOnInsert() {
        assertThatThrownBy(() -> watchlistEntryService.insertEntry(
                lucasId, ContentType.EPISODE, new WatchlistEntryCreationDTO(fightClub.getTmdbId())))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(watchlistEntryRepository, contentService, userRepository, contentRepository);
    }

    @Test
    @DisplayName("[insertEntry] Should Throw ConflictException With Specific Message - When Content Is Already In The Watchlist")
    void shouldThrowConflictExceptionWithSpecificMessageWhenContentIsAlreadyInTheWatchlist() {
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(watchlistEntryRepository.countByUserIdAndType(lucasId, ContentType.MOVIE)).thenReturn(0L);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(watchlistEntryRepository.save(any(WatchlistEntry.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_watchlist_entries_user_id_type_content_id"));

        assertThatThrownBy(() -> watchlistEntryService.insertEntry(
                lucasId, ContentType.MOVIE, new WatchlistEntryCreationDTO(fightClub.getTmdbId())))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This content is already in your watchlist");
    }

    @Test
    @DisplayName("[insertEntry] Should Throw ConflictException With Specific Message - When Position Was Just Taken By A Concurrent Insert")
    void shouldThrowConflictExceptionWithSpecificMessageWhenPositionWasJustTakenByAConcurrentInsert() {
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(watchlistEntryRepository.countByUserIdAndType(lucasId, ContentType.MOVIE)).thenReturn(0L);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(watchlistEntryRepository.save(any(WatchlistEntry.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_watchlist_entries_user_id_type_position"));

        assertThatThrownBy(() -> watchlistEntryService.insertEntry(
                lucasId, ContentType.MOVIE, new WatchlistEntryCreationDTO(fightClub.getTmdbId())))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This position was just taken by a concurrent insert");
    }

    @Test
    @DisplayName("[insertEntry] Should Throw ConflictException With Generic Message - When Constraint Name Is Unknown")
    void shouldThrowConflictExceptionWithGenericMessageWhenConstraintNameIsUnknown() {
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(watchlistEntryRepository.countByUserIdAndType(lucasId, ContentType.MOVIE)).thenReturn(0L);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(watchlistEntryRepository.save(any(WatchlistEntry.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_some_other_constraint"));

        assertThatThrownBy(() -> watchlistEntryService.insertEntry(
                lucasId, ContentType.MOVIE, new WatchlistEntryCreationDTO(fightClub.getTmdbId())))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Unable to insert this content into your watchlist");
    }

    @Test
    @DisplayName("[insertEntry] Should Throw ConflictException With Generic Message - When Cause Is Not ConstraintViolationException")
    void shouldThrowConflictExceptionWithGenericMessageWhenCauseIsNotConstraintViolationException() {
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(watchlistEntryRepository.countByUserIdAndType(lucasId, ContentType.MOVIE)).thenReturn(0L);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        DataIntegrityViolationException exception =
                new DataIntegrityViolationException("generic db error", new RuntimeException("unexpected cause"));
        when(watchlistEntryRepository.save(any(WatchlistEntry.class))).thenThrow(exception);

        assertThatThrownBy(() -> watchlistEntryService.insertEntry(
                lucasId, ContentType.MOVIE, new WatchlistEntryCreationDTO(fightClub.getTmdbId())))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Unable to insert this content into your watchlist");
    }

    @Test
    @DisplayName("[insertEntry] Should Throw ConflictException With Generic Message - When Cause Is Null")
    void shouldThrowConflictExceptionWithGenericMessageWhenCauseIsNull() {
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(watchlistEntryRepository.countByUserIdAndType(lucasId, ContentType.MOVIE)).thenReturn(0L);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        DataIntegrityViolationException exception = new DataIntegrityViolationException("no cause here");
        when(watchlistEntryRepository.save(any(WatchlistEntry.class))).thenThrow(exception);

        assertThatThrownBy(() -> watchlistEntryService.insertEntry(
                lucasId, ContentType.MOVIE, new WatchlistEntryCreationDTO(fightClub.getTmdbId())))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Unable to insert this content into your watchlist");
    }

    // ---------- removeEntry ----------

    @Test
    @DisplayName("[removeEntry] Should Delete And Bulk Shift Remaining Positions Down - When Entry Is Removed")
    void shouldDeleteAndBulkShiftRemainingPositionsDownWhenEntryIsRemoved() {
        WatchlistEntry toRemove = buildEntry(lucas, fightClub, ContentType.MOVIE, 2);
        when(watchlistEntryRepository.findById(toRemove.getId())).thenReturn(Optional.of(toRemove));

        watchlistEntryService.removeEntry(lucasId, ContentType.MOVIE, toRemove.getId());

        verify(watchlistEntryRepository).delete(toRemove);
        verify(watchlistEntryRepository, never()).save(any());
        verify(watchlistEntryRepository, times(1)).flush();
        verify(watchlistEntryRepository).parkPositionsInRange(
                lucasId, ContentType.MOVIE, 3, Integer.MAX_VALUE, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET);
        verify(watchlistEntryRepository).settleParkedPositions(
                lucasId, ContentType.MOVIE, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET, -1);
    }

    @Test
    @DisplayName("[removeEntry] Should Throw BadRequestException - When Type Is Season")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonOnRemove() {
        assertThatThrownBy(() -> watchlistEntryService.removeEntry(lucasId, ContentType.SEASON, UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(watchlistEntryRepository);
    }

    @Test
    @DisplayName("[removeEntry] Should Throw BadRequestException - When Type Is Episode")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeOnRemove() {
        assertThatThrownBy(() -> watchlistEntryService.removeEntry(lucasId, ContentType.EPISODE, UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(watchlistEntryRepository);
    }

    @Test
    @DisplayName("[removeEntry] Should Throw NotFoundException - When Entry Does Not Exist")
    void shouldThrowNotFoundExceptionWhenEntryDoesNotExist() {
        UUID missingId = UUID.randomUUID();
        when(watchlistEntryRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> watchlistEntryService.removeEntry(lucasId, ContentType.MOVIE, missingId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Watchlist entry not found");

        verify(watchlistEntryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[removeEntry] Should Throw NotFoundException - When Entry Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenEntryBelongsToADifferentUser() {
        WatchlistEntry marinasEntry = buildEntry(marina, fightClub, ContentType.MOVIE, 1);
        when(watchlistEntryRepository.findById(marinasEntry.getId())).thenReturn(Optional.of(marinasEntry));

        assertThatThrownBy(() -> watchlistEntryService.removeEntry(lucasId, ContentType.MOVIE, marinasEntry.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Watchlist entry not found");

        verify(watchlistEntryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[removeEntry] Should Throw NotFoundException - When Entry Type Does Not Match Path Type")
    void shouldThrowNotFoundExceptionWhenEntryTypeDoesNotMatchPathType() {
        WatchlistEntry movieEntry = buildEntry(lucas, fightClub, ContentType.MOVIE, 1);
        when(watchlistEntryRepository.findById(movieEntry.getId())).thenReturn(Optional.of(movieEntry));

        assertThatThrownBy(() -> watchlistEntryService.removeEntry(lucasId, ContentType.SERIES, movieEntry.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Watchlist entry not found");

        verify(watchlistEntryRepository, never()).delete(any());
    }

    // ---------- removeEntryIfPresent ----------

    @Test
    @DisplayName("[removeEntryIfPresent] Should Do Nothing - When No Entry Exists For That User And Content")
    void shouldDoNothingWhenNoEntryExistsForThatUserAndContentOnRemoveIfPresent() {
        UUID contentId = UUID.randomUUID();
        when(watchlistEntryRepository.findByUserIdAndTypeAndContentId(lucasId, ContentType.MOVIE, contentId))
                .thenReturn(Optional.empty());

        watchlistEntryService.removeEntryIfPresent(lucasId, ContentType.MOVIE, contentId);

        verify(watchlistEntryRepository, never()).delete(any());
        verify(watchlistEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("[removeEntryIfPresent] Should Delete And Bulk Shift Remaining Positions Down - When Entry Is Removed")
    void shouldDeleteAndBulkShiftRemainingPositionsDownWhenEntryIsRemovedOnRemoveIfPresent() {
        WatchlistEntry toRemove = buildEntry(lucas, fightClub, ContentType.MOVIE, 2);
        when(watchlistEntryRepository.findByUserIdAndTypeAndContentId(lucasId, ContentType.MOVIE, fightClub.getId()))
                .thenReturn(Optional.of(toRemove));

        watchlistEntryService.removeEntryIfPresent(lucasId, ContentType.MOVIE, fightClub.getId());

        verify(watchlistEntryRepository).delete(toRemove);
        verify(watchlistEntryRepository, never()).save(any());
        verify(watchlistEntryRepository, times(1)).flush();
        verify(watchlistEntryRepository).parkPositionsInRange(
                lucasId, ContentType.MOVIE, 3, Integer.MAX_VALUE, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET);
        verify(watchlistEntryRepository).settleParkedPositions(
                lucasId, ContentType.MOVIE, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET, -1);
    }

    // ---------- moveEntry ----------

    @Test
    @DisplayName("[moveEntry] Should Park Entry Then Bulk Shift Others Forward - When Moving To An Earlier Position")
    void shouldParkEntryThenBulkShiftOthersForwardWhenMovingToAnEarlierPosition() {
        WatchlistEntry d = buildEntry(lucas, buildContent("4", ContentType.MOVIE), ContentType.MOVIE, 4);
        when(watchlistEntryRepository.findById(d.getId())).thenReturn(Optional.of(d));
        when(watchlistEntryRepository.countByUserIdAndType(lucasId, ContentType.MOVIE)).thenReturn(4L);
        List<Integer> savedPositions = new ArrayList<>();
        when(watchlistEntryRepository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> {
            WatchlistEntry arg = invocation.getArgument(0);
            savedPositions.add(arg.getPosition());
            return arg;
        });
        when(watchlistEntryMapper.watchlistEntryToResponseDto(d)).thenReturn(buildResponseDto(d));

        watchlistEntryService.moveEntry(lucasId, ContentType.MOVIE, d.getId(), new WatchlistEntryReorderDTO(2));

        verify(watchlistEntryRepository, times(2)).save(any(WatchlistEntry.class));
        assertThat(savedPositions).containsExactly(5, 2);
        assertThat(d.getPosition()).isEqualTo(2);
        verify(watchlistEntryRepository).parkPositionsInRange(
                lucasId, ContentType.MOVIE, 2, 3, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET);
        verify(watchlistEntryRepository).settleParkedPositions(
                lucasId, ContentType.MOVIE, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET, 1);
        verify(watchlistEntryRepository, times(2)).flush();
    }

    @Test
    @DisplayName("[moveEntry] Should Park Entry Then Bulk Shift Others Backward - When Moving To A Later Position")
    void shouldParkEntryThenBulkShiftOthersBackwardWhenMovingToALaterPosition() {
        WatchlistEntry a = buildEntry(lucas, buildContent("1", ContentType.MOVIE), ContentType.MOVIE, 1);
        when(watchlistEntryRepository.findById(a.getId())).thenReturn(Optional.of(a));
        when(watchlistEntryRepository.countByUserIdAndType(lucasId, ContentType.MOVIE)).thenReturn(4L);
        List<Integer> savedPositions = new ArrayList<>();
        when(watchlistEntryRepository.save(any(WatchlistEntry.class))).thenAnswer(invocation -> {
            WatchlistEntry arg = invocation.getArgument(0);
            savedPositions.add(arg.getPosition());
            return arg;
        });
        when(watchlistEntryMapper.watchlistEntryToResponseDto(a)).thenReturn(buildResponseDto(a));

        watchlistEntryService.moveEntry(lucasId, ContentType.MOVIE, a.getId(), new WatchlistEntryReorderDTO(3));

        verify(watchlistEntryRepository, times(2)).save(any(WatchlistEntry.class));
        assertThat(savedPositions).containsExactly(5, 3);
        assertThat(a.getPosition()).isEqualTo(3);
        verify(watchlistEntryRepository).parkPositionsInRange(
                lucasId, ContentType.MOVIE, 2, 3, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET);
        verify(watchlistEntryRepository).settleParkedPositions(
                lucasId, ContentType.MOVIE, WatchlistEntryServiceImpl.POSITION_PARK_OFFSET, -1);
        verify(watchlistEntryRepository, times(2)).flush();
    }

    @Test
    @DisplayName("[moveEntry] Should Return Entry Without Saving - When New Position Equals Current Position")
    void shouldReturnEntryWithoutSavingWhenNewPositionEqualsCurrentPosition() {
        WatchlistEntry entry = buildEntry(lucas, fightClub, ContentType.MOVIE, 2);
        WatchlistEntryResponseDTO expectedDto = buildResponseDto(entry);
        when(watchlistEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(watchlistEntryRepository.countByUserIdAndType(lucasId, ContentType.MOVIE)).thenReturn(2L);
        when(watchlistEntryMapper.watchlistEntryToResponseDto(entry)).thenReturn(expectedDto);

        WatchlistEntryResponseDTO result = watchlistEntryService.moveEntry(
                lucasId, ContentType.MOVIE, entry.getId(), new WatchlistEntryReorderDTO(2));

        assertThat(result).isEqualTo(expectedDto);
        verify(watchlistEntryRepository, never()).save(any());
        verify(watchlistEntryRepository, never()).flush();
    }

    @Test
    @DisplayName("[moveEntry] Should Throw BadRequestException - When New Position Is Greater Than The Current Entry Count")
    void shouldThrowBadRequestExceptionWhenNewPositionIsGreaterThanTheCurrentEntryCount() {
        WatchlistEntry entry = buildEntry(lucas, fightClub, ContentType.MOVIE, 1);
        when(watchlistEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        when(watchlistEntryRepository.countByUserIdAndType(lucasId, ContentType.MOVIE)).thenReturn(1L);

        assertThatThrownBy(() -> watchlistEntryService.moveEntry(
                lucasId, ContentType.MOVIE, entry.getId(), new WatchlistEntryReorderDTO(2)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("position cannot be greater than 1, the last position in the watchlist");

        verify(watchlistEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("[moveEntry] Should Throw BadRequestException - When Type Is Season")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonOnMove() {
        assertThatThrownBy(() -> watchlistEntryService.moveEntry(
                lucasId, ContentType.SEASON, UUID.randomUUID(), new WatchlistEntryReorderDTO(1)))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(watchlistEntryRepository);
    }

    @Test
    @DisplayName("[moveEntry] Should Throw BadRequestException - When Type Is Episode")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeOnMove() {
        assertThatThrownBy(() -> watchlistEntryService.moveEntry(
                lucasId, ContentType.EPISODE, UUID.randomUUID(), new WatchlistEntryReorderDTO(1)))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(watchlistEntryRepository);
    }

    @Test
    @DisplayName("[moveEntry] Should Throw NotFoundException - When Entry Does Not Exist")
    void shouldThrowNotFoundExceptionWhenEntryDoesNotExistOnMove() {
        UUID missingId = UUID.randomUUID();
        when(watchlistEntryRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> watchlistEntryService.moveEntry(
                lucasId, ContentType.MOVIE, missingId, new WatchlistEntryReorderDTO(1)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Watchlist entry not found");
    }

    @Test
    @DisplayName("[moveEntry] Should Throw NotFoundException - When Entry Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenEntryBelongsToADifferentUserOnMove() {
        WatchlistEntry marinasEntry = buildEntry(marina, fightClub, ContentType.MOVIE, 1);
        when(watchlistEntryRepository.findById(marinasEntry.getId())).thenReturn(Optional.of(marinasEntry));

        assertThatThrownBy(() -> watchlistEntryService.moveEntry(
                lucasId, ContentType.MOVIE, marinasEntry.getId(), new WatchlistEntryReorderDTO(1)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Watchlist entry not found");
    }

    @Test
    @DisplayName("[moveEntry] Should Throw NotFoundException - When Entry Type Does Not Match Path Type")
    void shouldThrowNotFoundExceptionWhenEntryTypeDoesNotMatchPathTypeOnMove() {
        WatchlistEntry movieEntry = buildEntry(lucas, fightClub, ContentType.MOVIE, 1);
        when(watchlistEntryRepository.findById(movieEntry.getId())).thenReturn(Optional.of(movieEntry));

        assertThatThrownBy(() -> watchlistEntryService.moveEntry(
                lucasId, ContentType.SERIES, movieEntry.getId(), new WatchlistEntryReorderDTO(1)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Watchlist entry not found");
    }

    @Test
    @DisplayName("[moveEntry] Should Throw ConflictException - When A Concurrent Update Causes A Data Integrity Violation")
    void shouldThrowConflictExceptionWhenAConcurrentUpdateCausesADataIntegrityViolation() {
        WatchlistEntry a = buildEntry(lucas, fightClub, ContentType.MOVIE, 1);
        when(watchlistEntryRepository.findById(a.getId())).thenReturn(Optional.of(a));
        when(watchlistEntryRepository.countByUserIdAndType(lucasId, ContentType.MOVIE)).thenReturn(2L);
        when(watchlistEntryRepository.save(any(WatchlistEntry.class)))
                .thenThrow(new DataIntegrityViolationException("concurrent modification"));

        assertThatThrownBy(() -> watchlistEntryService.moveEntry(
                lucasId, ContentType.MOVIE, a.getId(), new WatchlistEntryReorderDTO(2)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Watchlist entry could not be reordered due to a concurrent update");
    }

    // ---------- helpers ----------

    private void stubEmptyWatchlistPage() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(watchlistEntryRepository.findByUserIdAndTypeOrderByPositionAsc(eq(lucasId), eq(ContentType.MOVIE), any(PageRequest.class)))
                .thenReturn(Page.empty());
    }

    private void stubContentResolution(Content content, ContentType type) {
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenReturn(new ContentRefDTO(content.getId(), content.getTmdbId(), type, null, null, null,
                        null, null, LocalDateTime.now(), LocalDateTime.now()));
    }

    private WatchlistEntry buildEntry(User user, Content content, ContentType type, Integer position) {
        LocalDateTime now = LocalDateTime.now();
        return WatchlistEntry.builder()
                .id(UUID.randomUUID())
                .user(user)
                .content(content)
                .type(type)
                .position(position)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private WatchlistEntryResponseDTO buildResponseDto(WatchlistEntry entry) {
        return new WatchlistEntryResponseDTO(
                entry.getId(), entry.getType(), null, entry.getPosition(), entry.getCreatedAt(), entry.getUpdatedAt());
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

    private DataIntegrityViolationException buildDataIntegrityViolationException(String constraintName) {
        ConstraintViolationException cve = new ConstraintViolationException(
                "constraint violated",
                null,
                constraintName
        );
        return new DataIntegrityViolationException("db error", cve);
    }
}