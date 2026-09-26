package com.watchwise.watchwise_api.contentposter.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.validation.TmdbPosterUrlPolicy;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentposter.dto.UserContentPosterResponseDTO;
import com.watchwise.watchwise_api.contentposter.entity.UserContentPoster;
import com.watchwise.watchwise_api.contentposter.repository.UserContentPosterRepository;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserContentPosterServiceImplTest {

    private static final String FIRST_POSTER = TmdbPosterUrlPolicy.PREFIX + "first.png";
    private static final String SECOND_POSTER = TmdbPosterUrlPolicy.PREFIX + "second.png";

    @Mock
    private UserContentPosterRepository userContentPosterRepository;

    @Mock
    private ContentRepository contentRepository;

    @InjectMocks
    private UserContentPosterServiceImpl userContentPosterService;

    @Captor
    private ArgumentCaptor<UUID> posterIdCaptor;

    private UUID ownerId;
    private UUID otherUserId;
    private UUID contentId;
    private Content content;

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        contentId = UUID.randomUUID();
        content = Content.builder()
                .id(contentId)
                .tmdbId("550")
                .type(ContentType.MOVIE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("[upsert] Should Write Using The Authenticated Owner - When Owner Id Is Provided")
    void shouldWriteUsingAuthenticatedOwnerWhenOwnerIdIsProvided() {
        UserContentPoster stored = buildPoster(ownerId, FIRST_POSTER);
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
        when(userContentPosterRepository.findByUserIdAndContentId(ownerId, contentId))
                .thenReturn(Optional.of(stored));

        userContentPosterService.upsert(ownerId, contentId, FIRST_POSTER);

        verify(userContentPosterRepository).upsert(posterIdCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(ownerId),
                org.mockito.ArgumentMatchers.eq(contentId),
                org.mockito.ArgumentMatchers.eq(FIRST_POSTER));
        assertThat(posterIdCaptor.getValue()).isNotNull();
        verify(userContentPosterRepository, never())
                .upsert(any(), org.mockito.ArgumentMatchers.eq(otherUserId), any(), any());
    }

    @Test
    @DisplayName("[upsert] Should Throw NotFoundException - When Content Does Not Exist")
    void shouldThrowNotFoundExceptionWhenContentDoesNotExist() {
        when(contentRepository.findById(contentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userContentPosterService.upsert(ownerId, contentId, FIRST_POSTER))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Content not found");

        verifyNoInteractions(userContentPosterRepository);
    }

    @Test
    @DisplayName("[upsert] Should Return Persisted Poster Response - When URL Is Valid")
    void shouldReturnPersistedPosterResponseWhenUrlIsValid() {
        LocalDateTime updatedAt = LocalDateTime.now();
        UserContentPoster stored = buildPoster(ownerId, FIRST_POSTER, updatedAt);
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
        when(userContentPosterRepository.findByUserIdAndContentId(ownerId, contentId))
                .thenReturn(Optional.of(stored));

        UserContentPosterResponseDTO result = userContentPosterService.upsert(ownerId, contentId, FIRST_POSTER);

        assertThat(result).isEqualTo(new UserContentPosterResponseDTO(contentId, FIRST_POSTER, updatedAt));
    }

    @Test
    @DisplayName("[upsert] Should Replace Existing Poster - When Same Owner And Content Are Upserted")
    void shouldReplaceExistingPosterWhenSameOwnerAndContentAreUpserted() {
        UserContentPoster replaced = buildPoster(ownerId, SECOND_POSTER);
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
        when(userContentPosterRepository.findByUserIdAndContentId(ownerId, contentId))
                .thenReturn(Optional.of(replaced));

        UserContentPosterResponseDTO result = userContentPosterService.upsert(ownerId, contentId, SECOND_POSTER);

        assertThat(result.customPosterUrl()).isEqualTo(SECOND_POSTER);
        verify(userContentPosterRepository).upsert(any(),
                org.mockito.ArgumentMatchers.eq(ownerId),
                org.mockito.ArgumentMatchers.eq(contentId),
                org.mockito.ArgumentMatchers.eq(SECOND_POSTER));
    }

    @Test
    @DisplayName("[delete] Should Clear Poster - When Owner Deletes Content Poster")
    void shouldClearPosterWhenOwnerDeletesContentPoster() {
        userContentPosterService.delete(ownerId, contentId);

        verify(userContentPosterRepository).deleteByUserIdAndContentId(ownerId, contentId);
        verifyNoInteractions(contentRepository);
    }

    @Test
    @DisplayName("[upsert] Should Throw BadRequestException - When Direct Service URL Is Invalid")
    void shouldThrowBadRequestExceptionWhenDirectServiceUrlIsInvalid() {
        String invalidUrl = "https://image.tmdb.org/t/p/w500/poster.png";

        assertThatThrownBy(() -> userContentPosterService.upsert(ownerId, contentId, invalidUrl))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("customPosterUrl must be a TMDB w342 poster URL");

        verifyNoInteractions(contentRepository, userContentPosterRepository);
    }

    @Test
    @DisplayName("[upsert] Should Propagate Database Conflict - When Repository Upsert Fails")
    void shouldPropagateDatabaseConflictWhenRepositoryUpsertFails() {
        DataIntegrityViolationException conflict = new DataIntegrityViolationException("conflict");
        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
        doThrow(conflict).when(userContentPosterRepository).upsert(any(),
                org.mockito.ArgumentMatchers.eq(ownerId),
                org.mockito.ArgumentMatchers.eq(contentId),
                org.mockito.ArgumentMatchers.eq(FIRST_POSTER));

        assertThatThrownBy(() -> userContentPosterService.upsert(ownerId, contentId, FIRST_POSTER))
                .isSameAs(conflict);
    }

    @Test
    @DisplayName("[findByUserAndContentIds] Should Return Empty Map - When Content Id Collection Is Empty")
    void shouldReturnEmptyMapWhenContentIdCollectionIsEmpty() {
        Map<UUID, String> result = userContentPosterService.findByUserAndContentIds(ownerId, List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(userContentPosterRepository);
    }

    @Test
    @DisplayName("[findByUserAndContentPairs] Should Resolve Exact Pairs In One Batch - When Reviews Have Multiple Authors")
    void shouldResolveExactPairsInOneBatchWhenReviewsHaveMultipleAuthors() {
        UUID secondOwnerId = UUID.randomUUID();
        UUID secondContentId = UUID.randomUUID();
        UserContentPosterRepository.UserContentPosterPairProjection first =
                pairProjection(ownerId, contentId, FIRST_POSTER);
        UserContentPosterRepository.UserContentPosterPairProjection second =
                pairProjection(secondOwnerId, secondContentId, SECOND_POSTER);
        UserContentPosterRepository.UserContentPosterPairProjection crossPair =
                pairProjection(ownerId, secondContentId, "https://image.tmdb.org/t/p/w342/cross-pair.png");
        UserContentPosterService.UserContentPosterKey firstKey =
                new UserContentPosterService.UserContentPosterKey(ownerId, contentId);
        UserContentPosterService.UserContentPosterKey secondKey =
                new UserContentPosterService.UserContentPosterKey(secondOwnerId, secondContentId);

        when(userContentPosterRepository.findByUserIdInAndContentIdIn(
                List.of(ownerId, secondOwnerId), List.of(contentId, secondContentId)))
                .thenReturn(List.of(first, second, crossPair));

        Map<UserContentPosterService.UserContentPosterKey, String> result =
                userContentPosterService.findByUserAndContentPairs(List.of(firstKey, secondKey));

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(
                firstKey, FIRST_POSTER,
                secondKey, SECOND_POSTER));
        verify(userContentPosterRepository).findByUserIdInAndContentIdIn(
                List.of(ownerId, secondOwnerId), List.of(contentId, secondContentId));
    }

    @Test
    @DisplayName("[findSeriesPosters] Should Return Empty Map - When Series Id Collection Is Empty")
    void shouldReturnEmptyMapWhenSeriesIdCollectionIsEmpty() {
        Map<String, String> result = userContentPosterService.findSeriesPosters(ownerId, List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(userContentPosterRepository);
    }

    private UserContentPoster buildPoster(UUID userId, String posterUrl) {
        return buildPoster(userId, posterUrl, LocalDateTime.now());
    }

    private UserContentPoster buildPoster(UUID userId, String posterUrl, LocalDateTime updatedAt) {
        return UserContentPoster.builder()
                .id(UUID.randomUUID())
                .customPosterUrl(posterUrl)
                .updatedAt(updatedAt)
                .createdAt(updatedAt.minusMinutes(1))
                .build();
    }

    private UserContentPosterRepository.UserContentPosterPairProjection pairProjection(
            UUID userId, UUID contentId, String posterUrl) {
        UserContentPosterRepository.UserContentPosterPairProjection projection =
                org.mockito.Mockito.mock(UserContentPosterRepository.UserContentPosterPairProjection.class);
        when(projection.getUserId()).thenReturn(userId);
        when(projection.getContentId()).thenReturn(contentId);
        when(projection.getCustomPosterUrl()).thenReturn(posterUrl);
        return projection;
    }
}
