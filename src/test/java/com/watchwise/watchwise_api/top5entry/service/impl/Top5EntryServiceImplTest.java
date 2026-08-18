package com.watchwise.watchwise_api.top5entry.service.impl;

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
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryCreationDTO;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryResponseDTO;
import com.watchwise.watchwise_api.top5entry.entity.Top5Entry;
import com.watchwise.watchwise_api.top5entry.mapper.Top5EntryMapper;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Top5EntryServiceImplTest {

    @Mock
    private Top5EntryRepository top5EntryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentService contentService;

    @Mock
    private FollowerRepository followerRepository;

    @Mock
    private Top5EntryMapper top5EntryMapper;

    @InjectMocks
    private Top5EntryServiceImpl top5EntryService;

    @Captor
    private ArgumentCaptor<Top5Entry> entryCaptor;

    @Captor
    private ArgumentCaptor<ContentRefCreationDTO> contentRefCreationCaptor;

    private UUID lucasId;
    private UUID marinaId;
    private User lucas;
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

        fightClub = buildContent("550", ContentType.MOVIE);
        pulpFiction = buildContent("680", ContentType.MOVIE);
        breakingBad = buildContent("1396", ContentType.SERIES);
    }

    // ---------- getTop5 ----------

    @Test
    @DisplayName("[getTop5] Should Return Mapped Entries In Order - When Viewer Is The Profile Owner")
    void shouldReturnMappedEntriesInOrderWhenViewerIsTheProfileOwner() {
        Top5Entry entry1 = buildEntry(lucas, fightClub, ContentType.MOVIE, 1);
        Top5Entry entry2 = buildEntry(lucas, pulpFiction, ContentType.MOVIE, 2);
        Top5EntryResponseDTO dto1 = buildResponseDto(entry1);
        Top5EntryResponseDTO dto2 = buildResponseDto(entry2);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of(entry1, entry2));
        when(top5EntryMapper.top5EntryToResponseDto(entry1)).thenReturn(dto1);
        when(top5EntryMapper.top5EntryToResponseDto(entry2)).thenReturn(dto2);

        List<Top5EntryResponseDTO> result = top5EntryService.getTop5(lucasId, lucasId, ContentType.MOVIE);

        assertThat(result).containsExactly(dto1, dto2);
    }

    @Test
    @DisplayName("[getTop5] Should Return Empty List - When User Has No Entries Of That Type")
    void shouldReturnEmptyListWhenUserHasNoEntriesOfThatType() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of());

        List<Top5EntryResponseDTO> result = top5EntryService.getTop5(lucasId, lucasId, ContentType.MOVIE);

        assertThat(result).isEmpty();
        verify(top5EntryMapper, never()).top5EntryToResponseDto(any());
    }

    @Test
    @DisplayName("[getTop5] Should Throw NotFoundException - When User Does Not Exist")
    void shouldThrowNotFoundExceptionWhenUserDoesNotExistOnGet() {
        when(userRepository.findById(lucasId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> top5EntryService.getTop5(lucasId, lucasId, ContentType.MOVIE))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verifyNoInteractions(top5EntryRepository);
    }

    @Test
    @DisplayName("[getTop5] Should Throw BadRequestException - When Type Is Season")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonOnGet() {
        assertThatThrownBy(() -> top5EntryService.getTop5(lucasId, lucasId, ContentType.SEASON))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(userRepository, top5EntryRepository);
    }

    @Test
    @DisplayName("[getTop5] Should Throw BadRequestException - When Type Is Episode")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeOnGet() {
        assertThatThrownBy(() -> top5EntryService.getTop5(lucasId, lucasId, ContentType.EPISODE))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(userRepository, top5EntryRepository);
    }

    @Test
    @DisplayName("[getTop5] Should Return Entries - When Target Profile Is Public And Viewer Is A Different User")
    void shouldReturnEntriesWhenTargetProfileIsPublicAndViewerIsADifferentUser() {
        UUID viewerId = UUID.randomUUID();
        lucas.setIsProfilePublic(true);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of());

        List<Top5EntryResponseDTO> result = top5EntryService.getTop5(viewerId, lucasId, ContentType.MOVIE);

        assertThat(result).isEmpty();
        verifyNoInteractions(followerRepository);
    }

    @Test
    @DisplayName("[getTop5] Should Return Entries - When Target Profile Is Private And Viewer Is An Accepted Follower")
    void shouldReturnEntriesWhenTargetProfileIsPrivateAndViewerIsAnAcceptedFollower() {
        UUID viewerId = UUID.randomUUID();
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(true);
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of());

        List<Top5EntryResponseDTO> result = top5EntryService.getTop5(viewerId, lucasId, ContentType.MOVIE);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[getTop5] Should Throw ForbiddenException - When Target Profile Is Private And Viewer Is Not An Accepted Follower")
    void shouldThrowForbiddenExceptionWhenTargetProfileIsPrivateAndViewerIsNotAnAcceptedFollower() {
        UUID viewerId = UUID.randomUUID();
        lucas.setIsProfilePublic(false);
        when(userRepository.findById(lucasId)).thenReturn(Optional.of(lucas));
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, lucasId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> top5EntryService.getTop5(viewerId, lucasId, ContentType.MOVIE))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");

        verifyNoInteractions(top5EntryRepository);
    }

    // ---------- insertEntry ----------

    @Test
    @DisplayName("[insertEntry] Should Insert At Position One - When List Is Empty")
    void shouldInsertAtPositionOneWhenListIsEmpty() {
        Top5Entry savedEntry = buildEntry(lucas, fightClub, ContentType.MOVIE, 1);
        Top5EntryResponseDTO expectedDto = buildResponseDto(savedEntry);
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(top5EntryRepository.save(any(Top5Entry.class))).thenReturn(savedEntry);
        when(top5EntryMapper.top5EntryToResponseDto(savedEntry)).thenReturn(expectedDto);

        Top5EntryResponseDTO result = top5EntryService.insertEntry(
                lucasId, ContentType.MOVIE, new Top5EntryCreationDTO(fightClub.getTmdbId(), null));

        assertThat(result).isEqualTo(expectedDto);
        verify(top5EntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getPosition()).isEqualTo(1);
        verify(top5EntryRepository, never()).delete(any());
        verify(top5EntryRepository, times(1)).flush();
        verify(contentService).getOrCreateReference(contentRefCreationCaptor.capture());
        assertThat(contentRefCreationCaptor.getValue())
                .isEqualTo(new ContentRefCreationDTO(fightClub.getTmdbId(), ContentType.MOVIE, null, null, null, null, null));
    }

    @Test
    @DisplayName("[insertEntry] Should Insert At Next Free Position - When Position Omitted And List Is Not Full")
    void shouldInsertAtNextFreePositionWhenPositionOmittedAndListIsNotFull() {
        Top5Entry existing1 = buildEntry(lucas, fightClub, ContentType.MOVIE, 1);
        Top5Entry existing2 = buildEntry(lucas, pulpFiction, ContentType.MOVIE, 2);
        Content thirdMovie = buildContent("111", ContentType.MOVIE);
        Top5Entry savedEntry = buildEntry(lucas, thirdMovie, ContentType.MOVIE, 3);
        stubContentResolution(thirdMovie, ContentType.MOVIE);
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of(existing1, existing2));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(thirdMovie.getId())).thenReturn(thirdMovie);
        when(top5EntryRepository.save(any(Top5Entry.class))).thenReturn(savedEntry);
        when(top5EntryMapper.top5EntryToResponseDto(savedEntry)).thenReturn(buildResponseDto(savedEntry));

        top5EntryService.insertEntry(
                lucasId, ContentType.MOVIE, new Top5EntryCreationDTO(thirdMovie.getTmdbId(), null));

        verify(top5EntryRepository).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getPosition()).isEqualTo(3);
        verify(top5EntryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[insertEntry] Should Shift Without Eviction - When Inserting Into A Position With Fewer Than Five Entries")
    void shouldShiftWithoutEvictionWhenInsertingIntoAPositionWithFewerThanFiveEntries() {
        Top5Entry pos1 = buildEntry(lucas, fightClub, ContentType.MOVIE, 1);
        Top5Entry pos2 = buildEntry(lucas, pulpFiction, ContentType.MOVIE, 2);
        Content newContent = buildContent("999", ContentType.MOVIE);
        Top5Entry savedEntry = buildEntry(lucas, newContent, ContentType.MOVIE, 1);
        stubContentResolution(newContent, ContentType.MOVIE);
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of(pos1, pos2));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(newContent.getId())).thenReturn(newContent);
        when(top5EntryRepository.save(any(Top5Entry.class))).thenReturn(savedEntry);
        when(top5EntryMapper.top5EntryToResponseDto(savedEntry)).thenReturn(buildResponseDto(savedEntry));

        top5EntryService.insertEntry(
                lucasId, ContentType.MOVIE, new Top5EntryCreationDTO(newContent.getTmdbId(), 1));

        verify(top5EntryRepository, times(3)).save(entryCaptor.capture());
        List<Top5Entry> saved = entryCaptor.getAllValues();
        assertThat(saved.get(0)).isEqualTo(pos2);
        assertThat(saved.get(0).getPosition()).isEqualTo(3);
        assertThat(saved.get(1)).isEqualTo(pos1);
        assertThat(saved.get(1).getPosition()).isEqualTo(2);
        assertThat(saved.get(2).getPosition()).isEqualTo(1);
        verify(top5EntryRepository, never()).delete(any());
        verify(top5EntryRepository, times(3)).flush();
    }

    @Test
    @DisplayName("[insertEntry] Should Shift And Evict Last Position - When Inserting Into A Full List At A Middle Position")
    void shouldShiftAndEvictLastPositionWhenInsertingIntoAFullListAtAMiddlePosition() {
        Top5Entry pos1 = buildEntry(lucas, buildContent("1", ContentType.MOVIE), ContentType.MOVIE, 1);
        Top5Entry pos2 = buildEntry(lucas, buildContent("2", ContentType.MOVIE), ContentType.MOVIE, 2);
        Top5Entry pos3 = buildEntry(lucas, buildContent("3", ContentType.MOVIE), ContentType.MOVIE, 3);
        Top5Entry pos4 = buildEntry(lucas, buildContent("4", ContentType.MOVIE), ContentType.MOVIE, 4);
        Top5Entry pos5 = buildEntry(lucas, buildContent("5", ContentType.MOVIE), ContentType.MOVIE, 5);
        Content newContent = buildContent("999", ContentType.MOVIE);
        Top5Entry savedEntry = buildEntry(lucas, newContent, ContentType.MOVIE, 2);
        stubContentResolution(newContent, ContentType.MOVIE);
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of(pos1, pos2, pos3, pos4, pos5));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(newContent.getId())).thenReturn(newContent);
        when(top5EntryRepository.save(any(Top5Entry.class))).thenReturn(savedEntry);
        when(top5EntryMapper.top5EntryToResponseDto(savedEntry)).thenReturn(buildResponseDto(savedEntry));

        top5EntryService.insertEntry(
                lucasId, ContentType.MOVIE, new Top5EntryCreationDTO(newContent.getTmdbId(), 2));

        verify(top5EntryRepository).delete(pos5);
        verify(top5EntryRepository, times(4)).save(entryCaptor.capture());
        List<Top5Entry> saved = entryCaptor.getAllValues();
        assertThat(saved.get(0)).isEqualTo(pos4);
        assertThat(saved.get(0).getPosition()).isEqualTo(5);
        assertThat(saved.get(1)).isEqualTo(pos3);
        assertThat(saved.get(1).getPosition()).isEqualTo(4);
        assertThat(saved.get(2)).isEqualTo(pos2);
        assertThat(saved.get(2).getPosition()).isEqualTo(3);
        assertThat(saved.get(3).getPosition()).isEqualTo(2);
        assertThat(saved.get(3).getContent()).isEqualTo(newContent);
        verify(top5EntryRepository, times(5)).flush();
    }

    @Test
    @DisplayName("[insertEntry] Should Evict Only The Position Five Entry - When Inserting At Position Five In A Full List")
    void shouldEvictOnlyThePositionFiveEntryWhenInsertingAtPositionFiveInAFullList() {
        Top5Entry pos1 = buildEntry(lucas, buildContent("1", ContentType.MOVIE), ContentType.MOVIE, 1);
        Top5Entry pos2 = buildEntry(lucas, buildContent("2", ContentType.MOVIE), ContentType.MOVIE, 2);
        Top5Entry pos3 = buildEntry(lucas, buildContent("3", ContentType.MOVIE), ContentType.MOVIE, 3);
        Top5Entry pos4 = buildEntry(lucas, buildContent("4", ContentType.MOVIE), ContentType.MOVIE, 4);
        Top5Entry pos5 = buildEntry(lucas, buildContent("5", ContentType.MOVIE), ContentType.MOVIE, 5);
        Content newContent = buildContent("999", ContentType.MOVIE);
        Top5Entry savedEntry = buildEntry(lucas, newContent, ContentType.MOVIE, 5);
        stubContentResolution(newContent, ContentType.MOVIE);
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of(pos1, pos2, pos3, pos4, pos5));
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(newContent.getId())).thenReturn(newContent);
        when(top5EntryRepository.save(any(Top5Entry.class))).thenReturn(savedEntry);
        when(top5EntryMapper.top5EntryToResponseDto(savedEntry)).thenReturn(buildResponseDto(savedEntry));

        top5EntryService.insertEntry(
                lucasId, ContentType.MOVIE, new Top5EntryCreationDTO(newContent.getTmdbId(), 5));

        verify(top5EntryRepository).delete(pos5);
        verify(top5EntryRepository, never()).delete(pos1);
        verify(top5EntryRepository, never()).delete(pos2);
        verify(top5EntryRepository, never()).delete(pos3);
        verify(top5EntryRepository, never()).delete(pos4);
        verify(top5EntryRepository, times(1)).save(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getPosition()).isEqualTo(5);
        assertThat(entryCaptor.getValue().getContent()).isEqualTo(newContent);
    }

    @Test
    @DisplayName("[insertEntry] Should Throw BadRequestException - When Position Is Omitted And List Already Has Five Entries")
    void shouldThrowBadRequestExceptionWhenPositionIsOmittedAndListAlreadyHasFiveEntries() {
        List<Top5Entry> fullList = List.of(
                buildEntry(lucas, buildContent("1", ContentType.MOVIE), ContentType.MOVIE, 1),
                buildEntry(lucas, buildContent("2", ContentType.MOVIE), ContentType.MOVIE, 2),
                buildEntry(lucas, buildContent("3", ContentType.MOVIE), ContentType.MOVIE, 3),
                buildEntry(lucas, buildContent("4", ContentType.MOVIE), ContentType.MOVIE, 4),
                buildEntry(lucas, buildContent("5", ContentType.MOVIE), ContentType.MOVIE, 5));
        Content newContent = buildContent("999", ContentType.MOVIE);
        stubContentResolution(newContent, ContentType.MOVIE);
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(fullList);

        assertThatThrownBy(() -> top5EntryService.insertEntry(
                lucasId, ContentType.MOVIE, new Top5EntryCreationDTO(newContent.getTmdbId(), null)))
                .isInstanceOf(BadRequestException.class);

        verify(top5EntryRepository, never()).save(any());
        verify(top5EntryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[insertEntry] Should Throw BadRequestException - When Position Is Greater Than The Next Available Position")
    void shouldThrowBadRequestExceptionWhenPositionIsGreaterThanTheNextAvailablePosition() {
        Top5Entry existing1 = buildEntry(lucas, fightClub, ContentType.MOVIE, 1);
        Content newContent = buildContent("999", ContentType.MOVIE);
        stubContentResolution(newContent, ContentType.MOVIE);
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of(existing1));

        assertThatThrownBy(() -> top5EntryService.insertEntry(
                lucasId, ContentType.MOVIE, new Top5EntryCreationDTO(newContent.getTmdbId(), 5)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("position cannot be greater than 2, the next available position");

        verify(top5EntryRepository, never()).save(any());
        verify(top5EntryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[insertEntry] Should Throw BadRequestException - When Type Is Season")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonOnInsert() {
        assertThatThrownBy(() -> top5EntryService.insertEntry(
                lucasId, ContentType.SEASON, new Top5EntryCreationDTO(fightClub.getTmdbId(), 1)))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(top5EntryRepository, contentService, userRepository, contentRepository);
    }

    @Test
    @DisplayName("[insertEntry] Should Throw BadRequestException - When Type Is Episode")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeOnInsert() {
        assertThatThrownBy(() -> top5EntryService.insertEntry(
                lucasId, ContentType.EPISODE, new Top5EntryCreationDTO(fightClub.getTmdbId(), 1)))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(top5EntryRepository, contentService, userRepository, contentRepository);
    }

    @Test
    @DisplayName("[insertEntry] Should Throw ConflictException With Specific Message - When Content Is Already In The Top 5")
    void shouldThrowConflictExceptionWithSpecificMessageWhenContentIsAlreadyInTheTop5() {
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(top5EntryRepository.save(any(Top5Entry.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_top5_entries_user_id_type_content_id"));

        assertThatThrownBy(() -> top5EntryService.insertEntry(
                lucasId, ContentType.MOVIE, new Top5EntryCreationDTO(fightClub.getTmdbId(), null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This content is already in your top 5");
    }

    @Test
    @DisplayName("[insertEntry] Should Throw ConflictException With Specific Message - When Position Is Already Taken")
    void shouldThrowConflictExceptionWithSpecificMessageWhenPositionIsAlreadyTaken() {
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(top5EntryRepository.save(any(Top5Entry.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_top5_entries_user_id_type_position"));

        assertThatThrownBy(() -> top5EntryService.insertEntry(
                lucasId, ContentType.MOVIE, new Top5EntryCreationDTO(fightClub.getTmdbId(), 1)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This position is already taken");
    }

    @Test
    @DisplayName("[insertEntry] Should Throw ConflictException With Generic Message - When Constraint Name Is Unknown")
    void shouldThrowConflictExceptionWithGenericMessageWhenConstraintNameIsUnknown() {
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        when(top5EntryRepository.save(any(Top5Entry.class)))
                .thenThrow(buildDataIntegrityViolationException("uq_some_other_constraint"));

        assertThatThrownBy(() -> top5EntryService.insertEntry(
                lucasId, ContentType.MOVIE, new Top5EntryCreationDTO(fightClub.getTmdbId(), 1)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Unable to insert this content into your top 5");
    }

    @Test
    @DisplayName("[insertEntry] Should Throw ConflictException With Generic Message - When Cause Is Not ConstraintViolationException")
    void shouldThrowConflictExceptionWithGenericMessageWhenCauseIsNotConstraintViolationException() {
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        DataIntegrityViolationException exception =
                new DataIntegrityViolationException("generic db error", new RuntimeException("unexpected cause"));
        when(top5EntryRepository.save(any(Top5Entry.class))).thenThrow(exception);

        assertThatThrownBy(() -> top5EntryService.insertEntry(
                lucasId, ContentType.MOVIE, new Top5EntryCreationDTO(fightClub.getTmdbId(), 1)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Unable to insert this content into your top 5");
    }

    @Test
    @DisplayName("[insertEntry] Should Throw ConflictException With Generic Message - When Cause Is Null")
    void shouldThrowConflictExceptionWithGenericMessageWhenCauseIsNull() {
        stubContentResolution(fightClub, ContentType.MOVIE);
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of());
        when(userRepository.getReferenceById(lucasId)).thenReturn(lucas);
        when(contentRepository.getReferenceById(fightClub.getId())).thenReturn(fightClub);
        DataIntegrityViolationException exception = new DataIntegrityViolationException("no cause here");
        when(top5EntryRepository.save(any(Top5Entry.class))).thenThrow(exception);

        assertThatThrownBy(() -> top5EntryService.insertEntry(
                lucasId, ContentType.MOVIE, new Top5EntryCreationDTO(fightClub.getTmdbId(), 1)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Unable to insert this content into your top 5");
    }

    // ---------- removeEntry ----------

    @Test
    @DisplayName("[removeEntry] Should Delete And Not Shift - When No Remaining Entry Has A Higher Position")
    void shouldDeleteAndNotShiftWhenNoRemainingEntryHasAHigherPosition() {
        Top5Entry toRemove = buildEntry(lucas, fightClub, ContentType.MOVIE, 5);
        Top5Entry remaining = buildEntry(lucas, pulpFiction, ContentType.MOVIE, 1);
        when(top5EntryRepository.findById(toRemove.getId())).thenReturn(Optional.of(toRemove));
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of(remaining));

        top5EntryService.removeEntry(lucasId, ContentType.MOVIE, toRemove.getId());

        verify(top5EntryRepository).delete(toRemove);
        verify(top5EntryRepository, never()).save(any());
        verify(top5EntryRepository, times(1)).flush();
    }

    @Test
    @DisplayName("[removeEntry] Should Shift Remaining Positions Down - When Entry Removed Is In The Middle")
    void shouldShiftRemainingPositionsDownWhenEntryRemovedIsInTheMiddle() {
        Top5Entry toRemove = buildEntry(lucas, fightClub, ContentType.MOVIE, 2);
        Top5Entry pos1 = buildEntry(lucas, pulpFiction, ContentType.MOVIE, 1);
        Top5Entry pos3 = buildEntry(lucas, breakingBad, ContentType.MOVIE, 3);
        Content content4 = buildContent("444", ContentType.MOVIE);
        Top5Entry pos4 = buildEntry(lucas, content4, ContentType.MOVIE, 4);
        when(top5EntryRepository.findById(toRemove.getId())).thenReturn(Optional.of(toRemove));
        when(top5EntryRepository.findByUserIdAndTypeOrderByPositionAsc(lucasId, ContentType.MOVIE))
                .thenReturn(List.of(pos1, pos3, pos4));

        top5EntryService.removeEntry(lucasId, ContentType.MOVIE, toRemove.getId());

        verify(top5EntryRepository).delete(toRemove);
        verify(top5EntryRepository, times(2)).save(entryCaptor.capture());
        List<Top5Entry> saved = entryCaptor.getAllValues();
        assertThat(saved.get(0)).isEqualTo(pos3);
        assertThat(saved.get(0).getPosition()).isEqualTo(2);
        assertThat(saved.get(1)).isEqualTo(pos4);
        assertThat(saved.get(1).getPosition()).isEqualTo(3);
        verify(top5EntryRepository, times(3)).flush();
    }

    @Test
    @DisplayName("[removeEntry] Should Throw BadRequestException - When Type Is Season")
    void shouldThrowBadRequestExceptionWhenTypeIsSeasonOnRemove() {
        assertThatThrownBy(() -> top5EntryService.removeEntry(lucasId, ContentType.SEASON, UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(top5EntryRepository);
    }

    @Test
    @DisplayName("[removeEntry] Should Throw BadRequestException - When Type Is Episode")
    void shouldThrowBadRequestExceptionWhenTypeIsEpisodeOnRemove() {
        assertThatThrownBy(() -> top5EntryService.removeEntry(lucasId, ContentType.EPISODE, UUID.randomUUID()))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(top5EntryRepository);
    }

    @Test
    @DisplayName("[removeEntry] Should Throw NotFoundException - When Entry Does Not Exist")
    void shouldThrowNotFoundExceptionWhenEntryDoesNotExist() {
        UUID missingId = UUID.randomUUID();
        when(top5EntryRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> top5EntryService.removeEntry(lucasId, ContentType.MOVIE, missingId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Top 5 entry not found");

        verify(top5EntryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[removeEntry] Should Throw NotFoundException - When Entry Belongs To A Different User")
    void shouldThrowNotFoundExceptionWhenEntryBelongsToADifferentUser() {
        User marina = User.builder()
                .id(marinaId)
                .username("marina")
                .email("marina@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Top5Entry marinasEntry = buildEntry(marina, fightClub, ContentType.MOVIE, 1);
        when(top5EntryRepository.findById(marinasEntry.getId())).thenReturn(Optional.of(marinasEntry));

        assertThatThrownBy(() -> top5EntryService.removeEntry(lucasId, ContentType.MOVIE, marinasEntry.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Top 5 entry not found");

        verify(top5EntryRepository, never()).delete(any());
    }

    @Test
    @DisplayName("[removeEntry] Should Throw NotFoundException - When Entry Type Does Not Match Path Type")
    void shouldThrowNotFoundExceptionWhenEntryTypeDoesNotMatchPathType() {
        Top5Entry movieEntry = buildEntry(lucas, fightClub, ContentType.MOVIE, 1);
        when(top5EntryRepository.findById(movieEntry.getId())).thenReturn(Optional.of(movieEntry));

        assertThatThrownBy(() -> top5EntryService.removeEntry(lucasId, ContentType.SERIES, movieEntry.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Top 5 entry not found");

        verify(top5EntryRepository, never()).delete(any());
    }

    // ---------- helpers ----------

    private void stubContentResolution(Content content, ContentType type) {
        when(contentService.getOrCreateReference(any(ContentRefCreationDTO.class)))
                .thenReturn(new ContentRefDTO(content.getId(), content.getTmdbId(), type, null, null, null,
                        null, null, LocalDateTime.now(), LocalDateTime.now()));
    }

    private Top5Entry buildEntry(User user, Content content, ContentType type, Integer position) {
        LocalDateTime now = LocalDateTime.now();
        return Top5Entry.builder()
                .id(UUID.randomUUID())
                .user(user)
                .content(content)
                .type(type)
                .position(position)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Top5EntryResponseDTO buildResponseDto(Top5Entry entry) {
        return new Top5EntryResponseDTO(
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