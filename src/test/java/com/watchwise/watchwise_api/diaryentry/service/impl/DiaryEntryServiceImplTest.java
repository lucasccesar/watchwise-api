package com.watchwise.watchwise_api.diaryentry.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.mapper.DiaryEntryMapper;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryEntryRepository;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

    @InjectMocks
    private DiaryEntryServiceImpl diaryEntryService;

    @Captor
    private ArgumentCaptor<DiaryEntry> entryCaptor;

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
        when(diaryEntryMapper.diaryEntryToResponseDto(entry)).thenReturn(dto);

        Page<DiaryEntryResponseDTO> result = diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 10);

        assertThat(result.getContent()).containsExactly(dto);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Return Empty Page - When User Has No Entries")
    void shouldReturnEmptyPageWhenUserHasNoEntries() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(eq(lucasId), any(PageRequest.class)))
                .thenReturn(Page.empty());

        Page<DiaryEntryResponseDTO> result = diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 10);

        assertThat(result.getContent()).isEmpty();
        verify(diaryEntryMapper, never()).diaryEntryToResponseDto(any());
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Throw NotFoundException - When User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenUserDoesNotExist() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 10))
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

        Page<DiaryEntryResponseDTO> result = diaryEntryService.getDiaryEntries(marinaId, lucasId, null, 1, 10);

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

        Page<DiaryEntryResponseDTO> result = diaryEntryService.getDiaryEntries(marinaId, lucasId, null, 1, 10);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateAndViewerIsNotAnAcceptedFollower() {
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(marinaId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(marinaId, lucasId, null, 1, 10))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");

        verifyNoInteractions(diaryEntryRepository);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Query By Watched Date Range - When Year Is Provided")
    void shouldQueryByWatchedDateRangeWhenYearIsProvided() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdAndWatchedDateBetweenOrderByCreatedAtDesc(
                eq(lucasId), eq(LocalDate.of(2024, 1, 1)), eq(LocalDate.of(2024, 12, 31)), any(PageRequest.class)))
                .thenReturn(Page.empty());

        diaryEntryService.getDiaryEntries(lucasId, lucasId, 2024, 1, 10);

        verify(diaryEntryRepository).findByUserIdAndWatchedDateBetweenOrderByCreatedAtDesc(
                eq(lucasId), eq(LocalDate.of(2024, 1, 1)), eq(LocalDate.of(2024, 12, 31)), any(PageRequest.class));
        verify(diaryEntryRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Query Without Date Filter - When Year Is Not Provided")
    void shouldQueryWithoutDateFilterWhenYearIsNotProvided() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(eq(lucasId), any(PageRequest.class)))
                .thenReturn(Page.empty());

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 10);

        verify(diaryEntryRepository, never())
                .findByUserIdAndWatchedDateBetweenOrderByCreatedAtDesc(any(), any(), any(), any());
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Throw BadRequestException - When Year Is Out Of Range")
    void shouldThrowBadRequestExceptionWhenYearIsOutOfRange() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(lucasId, lucasId, Integer.MAX_VALUE, 1, 10))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(diaryEntryRepository);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Use Default Page - When Page Number Is Null")
    void shouldUseDefaultPageWhenPageNumberIsNull() {
        stubEmptyDiaryPage();

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, null, 10);

        verify(diaryEntryRepository).findByUserIdOrderByCreatedAtDesc(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(DiaryEntryServiceImpl.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Use Default Page - When Page Number Is Zero")
    void shouldUseDefaultPageWhenPageNumberIsZero() {
        stubEmptyDiaryPage();

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 0, 10);

        verify(diaryEntryRepository).findByUserIdOrderByCreatedAtDesc(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(DiaryEntryServiceImpl.DEFAULT_PAGE);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Use Page Number Minus One - When Page Number Is Positive")
    void shouldUsePageNumberMinusOneWhenPageNumberIsPositive() {
        stubEmptyDiaryPage();

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 3, 10);

        verify(diaryEntryRepository).findByUserIdOrderByCreatedAtDesc(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Throw BadRequestException - When Page Number Is Negative")
    void shouldThrowBadRequestExceptionWhenPageNumberIsNegative() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(lucasId, lucasId, null, -1, 10))
                .isInstanceOf(BadRequestException.class);

        verify(diaryEntryRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Use Default Page Size - When Page Size Is Null")
    void shouldUseDefaultPageSizeWhenPageSizeIsNull() {
        stubEmptyDiaryPage();

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, null);

        verify(diaryEntryRepository).findByUserIdOrderByCreatedAtDesc(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(DiaryEntryServiceImpl.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Use Default Page Size - When Page Size Exceeds Limit")
    void shouldUseDefaultPageSizeWhenPageSizeExceedsLimit() {
        stubEmptyDiaryPage();

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 1001);

        verify(diaryEntryRepository).findByUserIdOrderByCreatedAtDesc(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(DiaryEntryServiceImpl.DEFAULT_PAGE_SIZE);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Use Provided Page Size - When Page Size Is Valid")
    void shouldUseProvidedPageSizeWhenPageSizeIsValid() {
        stubEmptyDiaryPage();

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 25);

        verify(diaryEntryRepository).findByUserIdOrderByCreatedAtDesc(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Use Provided Page Size - When Page Size Is At Max Limit")
    void shouldUseProvidedPageSizeWhenPageSizeIsAtMaxLimit() {
        stubEmptyDiaryPage();

        diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 1000);

        verify(diaryEntryRepository).findByUserIdOrderByCreatedAtDesc(eq(lucasId), pageRequestCaptor.capture());
        assertThat(pageRequestCaptor.getValue().getPageSize()).isEqualTo(1000);
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Throw BadRequestException - When Page Size Is Negative")
    void shouldThrowBadRequestExceptionWhenPageSizeIsNegative() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, -5))
                .isInstanceOf(BadRequestException.class);

        verify(diaryEntryRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Throw BadRequestException - When Page Size Is Zero")
    void shouldThrowBadRequestExceptionWhenPageSizeIsZero() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));

        assertThatThrownBy(() -> diaryEntryService.getDiaryEntries(lucasId, lucasId, null, 1, 0))
                .isInstanceOf(BadRequestException.class);

        verify(diaryEntryRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
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
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry)).thenReturn(expectedDto);
        LocalDate watchedDate = LocalDate.of(2024, 5, 1);

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null),
                "Great movie", 9, watchedDate, true, false, "https://example.com/poster.png");

        DiaryEntryResponseDTO result = diaryEntryService.createDiaryEntry(lucasId, dto);

        assertThat(result).isEqualTo(expectedDto);
        verify(diaryEntryRepository).save(entryCaptor.capture());
        DiaryEntry captured = entryCaptor.getValue();
        assertThat(captured.getUser()).isEqualTo(lucas);
        assertThat(captured.getContent()).isEqualTo(fightClub);
        assertThat(captured.getComment()).isEqualTo("Great movie");
        assertThat(captured.getScore()).isEqualTo(9);
        assertThat(captured.getWatchedDate()).isEqualTo(watchedDate);
        assertThat(captured.getIsRewatch()).isTrue();
        assertThat(captured.getWatchedInTheater()).isFalse();
        assertThat(captured.getCustomPosterUrl()).isEqualTo("https://example.com/poster.png");
        verify(contentService).getOrCreateReference(contentRefCreationCaptor.capture());
        assertThat(contentRefCreationCaptor.getValue())
                .isEqualTo(new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null));
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Default IsRewatch To False - When Not Provided")
    void shouldDefaultIsRewatchToFalseWhenNotProvided() {
        stubContentResolution(fightClub);
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        DiaryEntry savedEntry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(savedEntry);
        when(diaryEntryMapper.diaryEntryToResponseDto(savedEntry)).thenReturn(buildResponseDto(savedEntry));

        DiaryEntryCreationDTO dto = new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null),
                null, null, null, null, null, null);

        diaryEntryService.createDiaryEntry(lucasId, dto);

        verify(diaryEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getIsRewatch()).isFalse();
    }

    // ---------- updateDiaryEntry ----------

    @Test
    @DisplayName("[updateDiaryEntry] Should Throw NotFoundException - When Entry Does Not Exist")
    void shouldThrowNotFoundExceptionWhenEntryDoesNotExistOnUpdate() {
        UUID missingId = UUID.randomUUID();
        when(diaryEntryRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> diaryEntryService.updateDiaryEntry(lucasId, missingId, minimalCreationDto()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Diary entry not found");

        verify(diaryEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Throw NotFoundException - When Entry Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenEntryBelongsToADifferentUser() {
        DiaryEntry marinasEntry = buildEntry(marina, fightClub);
        when(diaryEntryRepository.findById(marinasEntry.getId())).thenReturn(Optional.of(marinasEntry));

        assertThatThrownBy(() -> diaryEntryService.updateDiaryEntry(lucasId, marinasEntry.getId(), minimalCreationDto()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Diary entry not found");

        verify(diaryEntryRepository, never()).save(any());
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Always Resolve And Set Content - When Content Is Unchanged")
    void shouldAlwaysResolveAndSetContentWhenContentIsUnchanged() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        stubContentResolution(fightClub);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(entry);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry)).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), minimalCreationDto());

        verify(contentService).getOrCreateReference(any(ContentRefCreationDTO.class));
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
        entry.setIsRewatch(true);
        entry.setWatchedInTheater(true);
        entry.setCustomPosterUrl("https://example.com/original.png");
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        stubContentResolution(fightClub);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(entry);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry)).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), minimalCreationDto());

        verify(diaryEntryRepository).save(entryCaptor.capture());
        DiaryEntry saved = entryCaptor.getValue();
        assertThat(saved.getComment()).isEqualTo("Original comment");
        assertThat(saved.getScore()).isEqualTo(7);
        assertThat(saved.getWatchedDate()).isEqualTo(LocalDate.of(2023, 1, 1));
        assertThat(saved.getIsRewatch()).isTrue();
        assertThat(saved.getWatchedInTheater()).isTrue();
        assertThat(saved.getCustomPosterUrl()).isEqualTo("https://example.com/original.png");
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Update Comment - When A Different Value Is Provided")
    void shouldUpdateCommentWhenADifferentValueIsProvided() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        entry.setComment("Old comment");
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        stubContentResolution(fightClub);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(entry);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry)).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null),
                "New comment", null, null, null, null, null));

        verify(diaryEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getComment()).isEqualTo("New comment");
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Update Score - When A Different Value Is Provided")
    void shouldUpdateScoreWhenADifferentValueIsProvided() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        entry.setScore(5);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        stubContentResolution(fightClub);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(entry);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry)).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null),
                null, 10, null, null, null, null));

        verify(diaryEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getScore()).isEqualTo(10);
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Update WatchedDate - When A Different Value Is Provided")
    void shouldUpdateWatchedDateWhenADifferentValueIsProvided() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        entry.setWatchedDate(LocalDate.of(2023, 1, 1));
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        stubContentResolution(fightClub);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(entry);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry)).thenReturn(buildResponseDto(entry));
        LocalDate newDate = LocalDate.of(2024, 3, 15);

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null),
                null, null, newDate, null, null, null));

        verify(diaryEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getWatchedDate()).isEqualTo(newDate);
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Update IsRewatch - When A Different Value Is Provided")
    void shouldUpdateIsRewatchWhenADifferentValueIsProvided() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        entry.setIsRewatch(false);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        stubContentResolution(fightClub);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(entry);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry)).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null),
                null, null, null, true, null, null));

        verify(diaryEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getIsRewatch()).isTrue();
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Update WatchedInTheater - When A Different Value Is Provided")
    void shouldUpdateWatchedInTheaterWhenADifferentValueIsProvided() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        entry.setWatchedInTheater(false);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        stubContentResolution(fightClub);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(entry);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry)).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null),
                null, null, null, null, true, null));

        verify(diaryEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getWatchedInTheater()).isTrue();
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Update CustomPosterUrl - When A Different Value Is Provided")
    void shouldUpdateCustomPosterUrlWhenADifferentValueIsProvided() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        entry.setCustomPosterUrl("https://example.com/old.png");
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));
        stubContentResolution(fightClub);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(diaryEntryRepository.save(any(DiaryEntry.class))).thenReturn(entry);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry)).thenReturn(buildResponseDto(entry));

        diaryEntryService.updateDiaryEntry(lucasId, entry.getId(), new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null),
                null, null, null, null, null, "https://example.com/new.png"));

        verify(diaryEntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getCustomPosterUrl()).isEqualTo("https://example.com/new.png");
    }

    // ---------- deleteDiaryEntry ----------

    @Test
    @DisplayName("[deleteDiaryEntry] Should Delete Entry - When Owner Requests It")
    void shouldDeleteEntryWhenOwnerRequestsIt() {
        DiaryEntry entry = buildEntry(lucas, fightClub);
        when(diaryEntryRepository.findById(entry.getId())).thenReturn(Optional.of(entry));

        diaryEntryService.deleteDiaryEntry(lucasId, entry.getId());

        verify(diaryEntryRepository).delete(entry);
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

    // ---------- helpers ----------

    private void stubEmptyDiaryPage() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(diaryEntryRepository.findByUserIdOrderByCreatedAtDesc(any(), any(PageRequest.class)))
                .thenReturn(Page.empty());
    }

    private void stubContentResolution(Content content) {
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenReturn(new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(), null, null, null,
                        LocalDateTime.now(), LocalDateTime.now()));
    }

    private DiaryEntryCreationDTO minimalCreationDto() {
        return new DiaryEntryCreationDTO(
                new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null),
                null, null, null, null, null, null);
    }

    private DiaryEntry buildEntry(User user, Content content) {
        LocalDateTime now = LocalDateTime.now();
        return DiaryEntry.builder()
                .id(UUID.randomUUID())
                .user(user)
                .content(content)
                .isRewatch(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private DiaryEntryResponseDTO buildResponseDto(DiaryEntry entry) {
        return new DiaryEntryResponseDTO(
                entry.getId(), entry.getUser().getId(), null, entry.getComment(), entry.getScore(),
                entry.getWatchedDate(), entry.getIsRewatch(), entry.getWatchedInTheater(),
                entry.getCustomPosterUrl(), entry.getCreatedAt(), entry.getUpdatedAt());
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