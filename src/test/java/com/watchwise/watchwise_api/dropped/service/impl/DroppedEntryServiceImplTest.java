package com.watchwise.watchwise_api.dropped.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.dropped.dto.DroppedEntryCreationDTO;
import com.watchwise.watchwise_api.dropped.dto.DroppedEntryResponseDTO;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.dropped.mapper.DroppedEntryMapper;
import com.watchwise.watchwise_api.dropped.repository.DroppedEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.watchlist.service.WatchlistEntryService;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DroppedEntryServiceImplTest {

    @Mock
    private DroppedEntryRepository droppedEntryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentService contentService;

    @Mock
    private FollowerRepository followerRepository;

    @Mock
    private DroppedEntryMapper droppedEntryMapper;

    @Mock
    private NewTransactionExecutor newTransactionExecutor;

    @Mock
    private WatchlistEntryService watchlistEntryService;

    @InjectMocks
    private DroppedEntryServiceImpl droppedEntryService;

    @Captor
    private ArgumentCaptor<DroppedEntry> entryCaptor;

    @Captor
    private ArgumentCaptor<PageRequest> pageRequestCaptor;

    private UUID lucasId;
    private UUID marinaId;
    private User lucas;
    private Content fightClub;
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

        fightClub = buildContent("550", ContentType.MOVIE);
        breakingBad = buildContent("1396", ContentType.SERIES);
    }

    // ---------- getDropped ----------

    @Test
    @DisplayName("[getDropped] Should Return Mapped Page - When Viewer Is The Profile Owner")
    void shouldReturnMappedPageWhenViewerIsTheProfileOwner() {
        DroppedEntry entry = buildEntry(lucas, fightClub, ContentType.MOVIE, null);
        DroppedEntryResponseDTO dto = buildResponseDto(entry);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(entry)));
        when(droppedEntryMapper.droppedEntryToResponseDto(entry)).thenReturn(dto);

        Page<DroppedEntryResponseDTO> result = droppedEntryService.getDropped(lucasId, lucasId, ContentType.MOVIE, 1, 10);

        assertThat(result.getContent()).containsExactly(dto);
    }

    @Test
    @DisplayName("[getDropped] Should Return Empty Page - When User Has No Entries Of That Type")
    void shouldReturnEmptyPageWhenUserHasNoEntriesOfThatType() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<DroppedEntryResponseDTO> result = droppedEntryService.getDropped(lucasId, lucasId, ContentType.MOVIE, 1, 10);

        assertThat(result.getContent()).isEmpty();
        verify(droppedEntryMapper, never()).droppedEntryToResponseDto(any());
    }

    @Test
    @DisplayName("[getDropped] Should Throw NotFoundException - When User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenUserDoesNotExist() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> droppedEntryService.getDropped(lucasId, lucasId, ContentType.MOVIE, 1, 10))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verifyNoInteractions(droppedEntryRepository);
    }

    @Test
    @DisplayName("[getDropped] Should Throw BadRequestException - When Type Is Season")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonOnGet() {
        assertThatThrownBy(() -> droppedEntryService.getDropped(lucasId, lucasId, ContentType.SEASON, 1, 10))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(userRepository, droppedEntryRepository);
    }

    @Test
    @DisplayName("[getDropped] Should Throw BadRequestException - When Type Is Episode")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeOnGet() {
        assertThatThrownBy(() -> droppedEntryService.getDropped(lucasId, lucasId, ContentType.EPISODE, 1, 10))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(userRepository, droppedEntryRepository);
    }

    @Test
    @DisplayName("[getDropped] Should Return Entries - When Target Profile Is Public And Viewer Is A Different User")
    void shouldReturnEntriesWhenTargetProfileIsPublicAndViewerIsADifferentUser() {
        lucas.setIsProfilePublic(true);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<DroppedEntryResponseDTO> result = droppedEntryService.getDropped(marinaId, lucasId, ContentType.MOVIE, 1, 10);

        assertThat(result.getContent()).isEmpty();
        verifyNoInteractions(followerRepository);
    }

    @Test
    @DisplayName("[getDropped] Should Return Entries - When Target Profile Is Private And Viewer Is An Accepted Follower")
    void shouldReturnEntriesWhenTargetProfileIsPrivateAndViewerIsAnAcceptedFollower() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<DroppedEntryResponseDTO> result = droppedEntryService.getDropped(marinaId, lucasId, ContentType.MOVIE, 1, 10);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[getDropped] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateAndViewerIsNotAnAcceptedFollower() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> droppedEntryService.getDropped(marinaId, lucasId, ContentType.MOVIE, 1, 10))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");

        verifyNoInteractions(droppedEntryRepository);
    }

    @Test
    @DisplayName("[getDropped] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPageWhenPageNumberIsNull() {
        stubEmptyDroppedPage();

        droppedEntryService.getDropped(lucasId, lucasId, ContentType.MOVIE, null, 10);

        verify(droppedEntryRepository).findByUserIdAndTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(DroppedEntryServiceImpl.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getDropped] Should Use Default Page - When Page Number Is Zero")
    void shouldUseDefaultPageWhenPageNumberIsZero() {
        stubEmptyDroppedPage();

        droppedEntryService.getDropped(lucasId, lucasId, ContentType.MOVIE, 0, 10);

        verify(droppedEntryRepository).findByUserIdAndTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(DroppedEntryServiceImpl.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getDropped] Should Use Page Number Minus One - When Page Number Is Positive")
    void shouldUsePageNumberMinusOneWhenPageNumberIsPositive() {
        stubEmptyDroppedPage();

        droppedEntryService.getDropped(lucasId, lucasId, ContentType.MOVIE, 3, 10);

        verify(droppedEntryRepository).findByUserIdAndTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("[getDropped] Should Throw BadRequestException - When Page Number Is Negative")
    void shouldThrowBadRequestExceptionWhenPageNumberIsNegative() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> droppedEntryService.getDropped(lucasId, lucasId, ContentType.MOVIE, -1, 10))
                .isInstanceOf(BadRequestException.class);

        verify(droppedEntryRepository, never()).findByUserIdAndTypeOrderByCreatedAtDesc(any(), any(), any());
    }

    @Test
    @DisplayName("[getDropped] Should Use Default Page Size - When Page Size Is Null")
    void shouldUseDefaultPageSizeWhenPageSizeIsNull() {
        stubEmptyDroppedPage();

        droppedEntryService.getDropped(lucasId, lucasId, ContentType.MOVIE, 1, null);

        verify(droppedEntryRepository).findByUserIdAndTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(DroppedEntryServiceImpl.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getDropped] Should Use Default Page Size - When Page Size Exceeds Limit")
    void shouldUseDefaultPageSizeWhenPageSizeExceedsLimit() {
        stubEmptyDroppedPage();

        droppedEntryService.getDropped(lucasId, lucasId, ContentType.MOVIE, 1, 1001);

        verify(droppedEntryRepository).findByUserIdAndTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(DroppedEntryServiceImpl.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getDropped] Should Use Provided Page Size - When Page Size Is Valid")
    void shouldUseProvidedPageSizeWhenPageSizeIsValid() {
        stubEmptyDroppedPage();

        droppedEntryService.getDropped(lucasId, lucasId, ContentType.MOVIE, 1, 25);

        verify(droppedEntryRepository).findByUserIdAndTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("[getDropped] Should Use Provided Page Size - When Page Size Is At Max Limit")
    void shouldUseProvidedPageSizeWhenPageSizeIsAtMaxLimit() {
        stubEmptyDroppedPage();

        droppedEntryService.getDropped(lucasId, lucasId, ContentType.MOVIE, 1, 1000);

        verify(droppedEntryRepository).findByUserIdAndTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(1000);
    }

    @Test
    @DisplayName("[getDropped] Should Throw BadRequestException - When Page Size Is Negative")
    void shouldThrowBadRequestExceptionWhenPageSizeIsNegative() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> droppedEntryService.getDropped(lucasId, lucasId, ContentType.MOVIE, 1, -5))
                .isInstanceOf(BadRequestException.class);

        verify(droppedEntryRepository, never()).findByUserIdAndTypeOrderByCreatedAtDesc(any(), any(), any());
    }

    @Test
    @DisplayName("[getDropped] Should Throw BadRequestException - When Page Size Is Zero")
    void shouldThrowBadRequestExceptionWhenPageSizeIsZero() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> droppedEntryService.getDropped(lucasId, lucasId, ContentType.MOVIE, 1, 0))
                .isInstanceOf(BadRequestException.class);

        verify(droppedEntryRepository, never()).findByUserIdAndTypeOrderByCreatedAtDesc(any(), any(), any());
    }

    // ---------- markAsDropped ----------

    @Test
    @DisplayName("[markAsDropped] Should Save New Entry With Comment - When Not Already Dropped")
    void shouldSaveNewEntryWithCommentWhenNotAlreadyDropped() {
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(droppedEntryRepository.findByUserIdAndTypeAndContentId(lucasId, ContentType.MOVIE, fightClub.getId()))
                .thenReturn(Optional.empty());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        stubNewTransactionPassthrough();

        droppedEntryService.markAsDropped(lucasId, ContentType.MOVIE, fightClub.getTmdbId(), new DroppedEntryCreationDTO("Lost interest"));

        verify(droppedEntryRepository).saveAndFlush(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getUser()).isEqualTo(lucas);
        assertThat(entryCaptor.getValue().getContent()).isEqualTo(fightClub);
        assertThat(entryCaptor.getValue().getType()).isEqualTo(ContentType.MOVIE);
        assertThat(entryCaptor.getValue().getComment()).isEqualTo("Lost interest");
    }

    @Test
    @DisplayName("[markAsDropped] Should Save New Entry Without Comment - When Body Is Omitted")
    void shouldSaveNewEntryWithoutCommentWhenBodyIsOmitted() {
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(droppedEntryRepository.findByUserIdAndTypeAndContentId(lucasId, ContentType.MOVIE, fightClub.getId()))
                .thenReturn(Optional.empty());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        stubNewTransactionPassthrough();

        droppedEntryService.markAsDropped(lucasId, ContentType.MOVIE, fightClub.getTmdbId(), null);

        verify(droppedEntryRepository).saveAndFlush(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getComment()).isNull();
    }

    @Test
    @DisplayName("[markAsDropped] Should Attempt Save In A New Transaction - When Not Already Dropped")
    void shouldAttemptSaveInANewTransactionWhenNotAlreadyDropped() {
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(droppedEntryRepository.findByUserIdAndTypeAndContentId(lucasId, ContentType.MOVIE, fightClub.getId()))
                .thenReturn(Optional.empty());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        stubNewTransactionPassthrough();

        droppedEntryService.markAsDropped(lucasId, ContentType.MOVIE, fightClub.getTmdbId(), null);

        verify(newTransactionExecutor).runInNewTransaction(any());
    }

    @Test
    @DisplayName("[markAsDropped] Should Remove The Matching Watchlist Entry - When Marking A New Drop")
    void shouldRemoveTheMatchingWatchlistEntryWhenMarkingANewDrop() {
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(droppedEntryRepository.findByUserIdAndTypeAndContentId(lucasId, ContentType.MOVIE, fightClub.getId()))
                .thenReturn(Optional.empty());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        stubNewTransactionPassthrough();

        droppedEntryService.markAsDropped(lucasId, ContentType.MOVIE, fightClub.getTmdbId(), null);

        verify(watchlistEntryService).removeEntryIfPresent(lucasId, ContentType.MOVIE, fightClub.getId());
    }

    @Test
    @DisplayName("[markAsDropped] Should Remove The Matching Watchlist Entry - When Already Dropped")
    void shouldRemoveTheMatchingWatchlistEntryWhenAlreadyDropped() {
        DroppedEntry existing = buildEntry(lucas, fightClub, ContentType.MOVIE, "Old comment");
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(droppedEntryRepository.findByUserIdAndTypeAndContentId(lucasId, ContentType.MOVIE, fightClub.getId()))
                .thenReturn(Optional.of(existing));

        droppedEntryService.markAsDropped(lucasId, ContentType.MOVIE, fightClub.getTmdbId(), null);

        verify(watchlistEntryService).removeEntryIfPresent(lucasId, ContentType.MOVIE, fightClub.getId());
    }

    @Test
    @DisplayName("[markAsDropped] Should Do Nothing - When Already Dropped And Comment Is Not Provided")
    void shouldDoNothingWhenAlreadyDroppedAndCommentIsNotProvided() {
        DroppedEntry existing = buildEntry(lucas, fightClub, ContentType.MOVIE, "Old comment");
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(droppedEntryRepository.findByUserIdAndTypeAndContentId(lucasId, ContentType.MOVIE, fightClub.getId()))
                .thenReturn(Optional.of(existing));

        droppedEntryService.markAsDropped(lucasId, ContentType.MOVIE, fightClub.getTmdbId(), null);

        verify(droppedEntryRepository, never()).save(any());
        verify(droppedEntryRepository, never()).saveAndFlush(any());
        verifyNoInteractions(newTransactionExecutor);
    }

    @Test
    @DisplayName("[markAsDropped] Should Overwrite The Comment - When Already Dropped And Comment Is Provided")
    void shouldOverwriteTheCommentWhenAlreadyDroppedAndCommentIsProvided() {
        DroppedEntry existing = buildEntry(lucas, fightClub, ContentType.MOVIE, "Old comment");
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(droppedEntryRepository.findByUserIdAndTypeAndContentId(lucasId, ContentType.MOVIE, fightClub.getId()))
                .thenReturn(Optional.of(existing));

        droppedEntryService.markAsDropped(lucasId, ContentType.MOVIE, fightClub.getTmdbId(), new DroppedEntryCreationDTO("New comment"));

        verify(droppedEntryRepository).save(existing);
        assertThat(existing.getComment()).isEqualTo("New comment");
    }

    @Test
    @DisplayName("[markAsDropped] Should Throw BadRequestException - When Type Is Season")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonOnMark() {
        assertThatThrownBy(() -> droppedEntryService.markAsDropped(lucasId, ContentType.SEASON, "550", null))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(droppedEntryRepository, contentService, userRepository, contentRepository);
    }

    @Test
    @DisplayName("[markAsDropped] Should Throw BadRequestException - When Type Is Episode")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeOnMark() {
        assertThatThrownBy(() -> droppedEntryService.markAsDropped(lucasId, ContentType.EPISODE, "550", null))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(droppedEntryRepository, contentService, userRepository, contentRepository);
    }

    @Test
    @DisplayName("[markAsDropped] Should Resolve Successfully - When Save Throws DataIntegrityViolationException But Row Now Exists")
    void shouldResolveSuccessfullyWhenSaveThrowsDataIntegrityViolationExceptionButRowNowExists() {
        DroppedEntry recovered = buildEntry(lucas, fightClub, ContentType.MOVIE, null);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(droppedEntryRepository.findByUserIdAndTypeAndContentId(lucasId, ContentType.MOVIE, fightClub.getId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(recovered));
        stubNewTransactionThrowing(new DataIntegrityViolationException("duplicate"));

        assertThatCode(() -> droppedEntryService.markAsDropped(lucasId, ContentType.MOVIE, fightClub.getTmdbId(), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("[markAsDropped] Should Apply The Comment To The Recovered Row - When Save Throws DataIntegrityViolationException But Row Now Exists")
    void shouldApplyTheCommentToTheRecoveredRowWhenSaveThrowsDataIntegrityViolationExceptionButRowNowExists() {
        DroppedEntry recovered = buildEntry(lucas, fightClub, ContentType.MOVIE, null);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(droppedEntryRepository.findByUserIdAndTypeAndContentId(lucasId, ContentType.MOVIE, fightClub.getId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(recovered));
        stubNewTransactionThrowing(new DataIntegrityViolationException("duplicate"));

        droppedEntryService.markAsDropped(lucasId, ContentType.MOVIE, fightClub.getTmdbId(), new DroppedEntryCreationDTO("Concurrent comment"));

        verify(droppedEntryRepository).save(recovered);
        assertThat(recovered.getComment()).isEqualTo("Concurrent comment");
    }

    @Test
    @DisplayName("[markAsDropped] Should Rethrow DataIntegrityViolationException - When Row Still Does Not Exist After Save Fails")
    void shouldRethrowDataIntegrityViolationExceptionWhenRowStillDoesNotExistAfterSaveFails() {
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(droppedEntryRepository.findByUserIdAndTypeAndContentId(lucasId, ContentType.MOVIE, fightClub.getId()))
                .thenReturn(Optional.empty());
        DataIntegrityViolationException exception = new DataIntegrityViolationException("unexpected db error");
        stubNewTransactionThrowing(exception);

        assertThatThrownBy(() -> droppedEntryService.markAsDropped(lucasId, ContentType.MOVIE, fightClub.getTmdbId(), null))
                .isSameAs(exception);
    }

    // ---------- unmarkAsDropped ----------

    @Test
    @DisplayName("[unmarkAsDropped] Should Delete The Entry - When It Exists")
    void shouldDeleteTheEntryWhenItExists() {
        DroppedEntry entry = buildEntry(lucas, fightClub, ContentType.MOVIE, null);
        when(contentRepository.findByTmdbIdAndType(fightClub.getTmdbId(), ContentType.MOVIE)).thenReturn(Optional.of(fightClub));
        when(droppedEntryRepository.findByUserIdAndTypeAndContentId(lucasId, ContentType.MOVIE, fightClub.getId()))
                .thenReturn(Optional.of(entry));

        droppedEntryService.unmarkAsDropped(lucasId, ContentType.MOVIE, fightClub.getTmdbId());

        verify(droppedEntryRepository).delete(entry);
    }

    @Test
    @DisplayName("[unmarkAsDropped] Should Do Nothing - When The Content Does Not Exist Yet")
    void shouldDoNothingWhenTheContentDoesNotExistYet() {
        when(contentRepository.findByTmdbIdAndType("550", ContentType.MOVIE)).thenReturn(Optional.empty());

        droppedEntryService.unmarkAsDropped(lucasId, ContentType.MOVIE, "550");

        verify(droppedEntryRepository, never()).delete(any());
        verifyNoInteractions(droppedEntryRepository);
    }

    @Test
    @DisplayName("[unmarkAsDropped] Should Do Nothing - When The Entry Does Not Exist For That User")
    void shouldDoNothingWhenTheEntryDoesNotExistForThatUser() {
        when(contentRepository.findByTmdbIdAndType(fightClub.getTmdbId(), ContentType.MOVIE)).thenReturn(Optional.of(fightClub));
        when(droppedEntryRepository.findByUserIdAndTypeAndContentId(lucasId, ContentType.MOVIE, fightClub.getId()))
                .thenReturn(Optional.empty());

        droppedEntryService.unmarkAsDropped(lucasId, ContentType.MOVIE, fightClub.getTmdbId());

        verify(droppedEntryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[unmarkAsDropped] Should Throw BadRequestException - When Type Is Season")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonOnUnmark() {
        assertThatThrownBy(() -> droppedEntryService.unmarkAsDropped(lucasId, ContentType.SEASON, "550"))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(droppedEntryRepository, contentRepository);
    }

    @Test
    @DisplayName("[unmarkAsDropped] Should Throw BadRequestException - When Type Is Episode")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeOnUnmark() {
        assertThatThrownBy(() -> droppedEntryService.unmarkAsDropped(lucasId, ContentType.EPISODE, "550"))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(droppedEntryRepository, contentRepository);
    }

    // ---------- helpers ----------

    private void stubEmptyDroppedPage() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(droppedEntryRepository.findByUserIdAndTypeOrderByCreatedAtDesc(eq(lucasId), eq(ContentType.MOVIE), any(PageRequest.class)))
                .thenReturn(Page.empty());
    }

    private void stubContentResolution(Content content, ContentType type) {
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenReturn(new ContentRefDTO(content.getId(), content.getTmdbId(), type, null, null, null,
                        null, null, LocalDateTime.now(), LocalDateTime.now()));
    }

    private void stubNewTransactionPassthrough() {
        when(newTransactionExecutor.runInNewTransaction(any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
    }

    private void stubNewTransactionThrowing(RuntimeException exception) {
        when(newTransactionExecutor.runInNewTransaction(any())).thenThrow(exception);
    }

    private DroppedEntry buildEntry(User user, Content content, ContentType type, String comment) {
        LocalDateTime now = LocalDateTime.now();
        return DroppedEntry.builder()
                .id(UUID.randomUUID())
                .user(user)
                .content(content)
                .type(type)
                .comment(comment)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private DroppedEntryResponseDTO buildResponseDto(DroppedEntry entry) {
        return new DroppedEntryResponseDTO(
                entry.getId(), entry.getType(), null, entry.getComment(), entry.getCreatedAt(), entry.getUpdatedAt());
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
}
