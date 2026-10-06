package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.dto.GenreCountDTO;
import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.summary.dto.ProfileHighlightContentDTO;
import com.watchwise.watchwise_api.summary.dto.ProfileRewatchKind;
import com.watchwise.watchwise_api.summary.dto.RatingCountDTO;
import com.watchwise.watchwise_api.summary.dto.SummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.WatchTimeDTO;
import com.watchwise.watchwise_api.summary.service.ProfileDisplayMetadataResolver;
import com.watchwise.watchwise_api.summary.service.ProfileSummaryDataReader;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileSummaryReaderImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private FollowerRepository followerRepository;

    @Mock
    private ProfileSummaryDataReader dataReader;

    @Mock
    private ProfileDisplayMetadataResolver displayMetadataResolver;

    private ProfileSummaryReaderImpl reader;
    private UUID viewerId;
    private UUID userId;
    private User target;

    @BeforeEach
    void setUp() {
        reader = new ProfileSummaryReaderImpl(userRepository, followerRepository, dataReader, displayMetadataResolver);
        viewerId = UUID.randomUUID();
        userId = UUID.randomUUID();
        target = User.builder().id(userId).isProfilePublic(true).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(target));
    }

    @Test
    void shouldAssembleSummaryAndComputeRatingsSummaryFromDistinctDistribution() {
        Content movie = Content.builder().id(UUID.randomUUID()).type(ContentType.MOVIE).tmdbId("550").build();
        ProfileSummaryDataReader.Snapshot snapshot = new ProfileSummaryDataReader.Snapshot(
                new WatchTimeDTO(500L, 120L, 4L, 1L),
                List.of(new GenreCountDTO("Drama", 2L)),
                List.of(new RatingCountDTO(8, 2L), new RatingCountDTO(10, 1L)),
                List.of(), List.of(), List.of(),
                new ProfileSummaryDataReader.RewatchData(ProfileRewatchKind.MOST_REWATCHED, movie, 3L),
                null);
        when(dataReader.read(userId, ContentType.MOVIE)).thenReturn(snapshot);
        ProfileHighlightContentDTO highlight = new ProfileHighlightContentDTO(
                ContentType.MOVIE, movie.getId(), "550", null, "Fight Club", 1999, "/poster.jpg", null);
        when(displayMetadataResolver.resolveStoredContent(userId, movie)).thenReturn(highlight);

        SummaryResponseDTO result = reader.read(viewerId, userId, ContentType.MOVIE);

        assertThat(result.watchTime().totalWatchedCount()).isEqualTo(4L);
        assertThat(result.ratingsSummary().totalRatings()).isEqualTo(3L);
        assertThat(result.ratingsSummary().averageScore()).isEqualTo(26.0 / 3.0);
        assertThat(result.highlights().rewatch().content()).isEqualTo(highlight);
        assertThat(result.genreCounts()).containsExactly(new GenreCountDTO("Drama", 2L));
    }

    @Test
    void shouldRejectPrivateProfileBeforeReadingAggregates() {
        target.setIsProfilePublic(false);
        when(followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, userId, FollowStatus.ACCEPTED))
                .thenReturn(false);

        assertThatThrownBy(() -> reader.read(viewerId, userId, ContentType.MOVIE))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("This user profile is private");

        verify(dataReader, never()).read(any(), any());
    }

    @Test
    void shouldRejectUnsupportedSummaryTypeBeforeReadingAggregates() {
        assertThatThrownBy(() -> reader.read(viewerId, userId, ContentType.EPISODE))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("type must be one of: MOVIE, SERIES");

        verify(dataReader, never()).read(any(), any());
    }

    @Test
    void shouldThrowNotFoundWhenTargetDoesNotExist() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reader.read(viewerId, userId, ContentType.MOVIE))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verify(dataReader, never()).read(any(), any());
    }
}
