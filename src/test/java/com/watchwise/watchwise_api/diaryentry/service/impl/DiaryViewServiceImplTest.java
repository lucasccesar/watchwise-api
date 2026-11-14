package com.watchwise.watchwise_api.diaryentry.service.impl;

import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.contentrating.entity.ContentRatingPass;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingPassRepository;
import com.watchwise.watchwise_api.contentreview.repository.ContentReviewRepository;
import com.watchwise.watchwise_api.diarycompletion.service.DiaryCompletionService;
import com.watchwise.watchwise_api.diaryentry.dto.*;
import com.watchwise.watchwise_api.diaryentry.repository.DiaryViewRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.user.service.UserVisibilityService;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiaryViewServiceImplTest {
    @Mock UserRepository userRepository;
    @Mock UserBlockAccessService userBlockAccessService;
    @Mock UserVisibilityService userVisibilityService;
    @Mock DiaryCompletionService diaryCompletionService;
    @Mock DiaryViewRepository diaryViewRepository;
    @Mock ContentRepository contentRepository;
    @Mock ContentMapper contentMapper;
    @Mock ContentCardAssembler contentCardAssembler;
    @Mock ContentRatingRepository contentRatingRepository;
    @Mock ContentRatingPassRepository contentRatingPassRepository;
    @Mock ContentReviewRepository contentReviewRepository;
    @Mock PageRequestFactory pageRequestFactory;
    @InjectMocks DiaryViewServiceImpl service;

    @Test
    void shouldBypassGroupingWhenReviewFilterIsTrue() {
        UUID owner = UUID.randomUUID();
        when(userRepository.findById(owner)).thenReturn(Optional.of(User.builder().id(owner).build()));
        when(pageRequestFactory.build(null, null)).thenReturn(PageRequest.of(0, 20));
        when(diaryViewRepository.findPage(eq(owner), any(), any(), any(), any(), any())).thenReturn(Page.empty());
        var result = service.getView(owner, owner, new DiaryViewFilter(null, null, null, null, null, true),
                null, null, true);
        assertThat(result).isEmpty();
        verify(diaryViewRepository, never()).findGroupedPage(any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldUseRatingForEachDerivedCompletionPass() {
        UUID owner = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();
        UUID passOneEntry = UUID.randomUUID();
        UUID passTwoEntry = UUID.randomUUID();
        Instant orderedAt = Instant.parse("2026-11-14T12:00:00Z");
        LocalDate watchedDate = LocalDate.of(2026, 11, 13);
        User user = User.builder().id(owner).preferredRegion("US").preferredLanguage("en-US").build();
        Content content = Content.builder()
                .id(contentId)
                .tmdbId("tv-pass-history")
                .type(ContentType.SERIES)
                .build();
        ContentRefDTO contentRef = new ContentRefDTO(
                contentId, "tv-pass-history", ContentType.SERIES, null, null, null, null, null, null, null);
        var passOne = new DiaryViewRepository.DiaryViewKey(
                "DERIVED_COMPLETION", null, contentId, ContentType.SERIES, "tv-pass-history", null,
                null, null, 1, passOneEntry, watchedDate, orderedAt, orderedAt, passOneEntry, null);
        var passTwo = new DiaryViewRepository.DiaryViewKey(
                "DERIVED_COMPLETION", null, contentId, ContentType.SERIES, "tv-pass-history", null,
                null, null, 2, passTwoEntry, watchedDate, orderedAt.plusSeconds(1), orderedAt.plusSeconds(1),
                passTwoEntry, null);
        ContentRatingPass passOneRating = ContentRatingPass.builder()
                .id(UUID.randomUUID()).userId(owner).contentId(contentId).contentType(ContentType.SERIES)
                .watchNumber(1).score(9).createdAt(orderedAt).updatedAt(orderedAt).build();
        ContentRatingPass passTwoRating = ContentRatingPass.builder()
                .id(UUID.randomUUID()).userId(owner).contentId(contentId).contentType(ContentType.SERIES)
                .watchNumber(2).score(10).createdAt(orderedAt).updatedAt(orderedAt).build();
        when(userRepository.findById(owner)).thenReturn(Optional.of(user));
        when(pageRequestFactory.build(null, null)).thenReturn(PageRequest.of(0, 20));
        when(diaryViewRepository.findPage(eq(owner), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(passOne, passTwo)));
        when(contentRepository.findAllById(List.of(contentId))).thenReturn(List.of(content));
        when(contentMapper.contentToContentRefDto(content)).thenReturn(contentRef);
        when(contentRatingRepository.findByUserIdAndContentIdIn(owner, List.of(contentId)))
                .thenReturn(List.of());
        when(contentRatingPassRepository.findByUserIdAndContentIdInAndWatchNumberIn(
                owner, List.of(contentId), List.of(1, 2)))
                .thenReturn(List.of(passOneRating, passTwoRating));
        when(contentReviewRepository.findByUserIdAndContentIdInOrderByCreatedAtDesc(owner, List.of(contentId)))
                .thenReturn(List.of());

        var result = service.getView(owner, owner,
                new DiaryViewFilter(null, null, null, null, null, null), null, null);

        assertThat(result.getContent())
                .extracting(DiaryViewItemDTO::watchNumber, DiaryViewItemDTO::currentScore)
                .containsExactly(tuple(1, 9), tuple(2, 10));
    }

    @Test
    void shouldReturnSeriesReferenceEvenWithoutPersistedParent() {
        UUID owner = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 11, 4);
        Instant now = Instant.now();
        when(userRepository.findById(owner)).thenReturn(Optional.of(User.builder().id(owner).build()));
        when(pageRequestFactory.build(null, null)).thenReturn(PageRequest.of(0, 20));
        var key = new DiaryViewRepository.DiaryViewKey("PERSISTED_ENTRY", entry, UUID.randomUUID(),
                ContentType.EPISODE, null, "1396", 1, 2, 1, entry, date, now, now, entry, null);
        var grouped = new DiaryViewRepository.GroupedDiaryViewKey(key, 2, ContentType.EPISODE,
                date, null, null, "1396", 1, List.of(1), 2, 90L);
        when(diaryViewRepository.findGroupedPage(eq(owner), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(grouped)));
        when(contentCardAssembler.assemble(anyCollection(), any(), any(ContentCardFieldSet.class)))
                .thenReturn(java.util.Map.of());
        var result = service.getView(owner, owner, new DiaryViewFilter(null, null, null, null, null, null),
                null, null, true);
        assertThat(result.getContent()).singleElement().isInstanceOfSatisfying(DiaryViewGroupedItemDTO.class, item -> {
            assertThat(item.kind()).isEqualTo("GROUPED");
            assertThat(item.content().type()).isEqualTo(ContentType.SERIES);
            assertThat(item.content().tmdbId()).isEqualTo("1396");
            assertThat(item.groupType()).isEqualTo(ContentType.EPISODE);
            assertThat(item.episodeCount()).isEqualTo(2);
        });
        verify(userBlockAccessService).assertContentVisible(owner, owner);
        verify(userVisibilityService).assertCanView(owner, owner);
    }

    @Test
    void shouldExposeSeasonRatingTargetSeparatelyFromGroupedSeriesCard() {
        UUID owner = UUID.randomUUID();
        UUID seriesContentId = UUID.randomUUID();
        UUID seasonContentId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 11, 4);
        Instant now = Instant.now();
        Content series = Content.builder().id(seriesContentId).tmdbId("1396").type(ContentType.SERIES).build();
        ContentRefDTO seriesReference = new ContentRefDTO(
                seriesContentId, "1396", ContentType.SERIES, null, null, null, null, null, null, null);
        var key = new DiaryViewRepository.DiaryViewKey("DERIVED_COMPLETION", null, seasonContentId,
                ContentType.SEASON, null, "1396", 1, null, 1, entryId, date, now, now, entryId, 9);
        var grouped = new DiaryViewRepository.GroupedDiaryViewKey(key, 3, ContentType.SEASON,
                date, seriesContentId, seasonContentId, "1396", 1, List.of(1), 2, 90L);
        ContentCardDTO seriesCard = new ContentCardDTO(
                seriesContentId, ContentType.SERIES, "1396", null, null, null, "Series",
                null, null, null, null, null, null, null, null, List.of(), null, null,
                ContentPreviewStatus.AVAILABLE);
        when(userRepository.findById(owner)).thenReturn(Optional.of(User.builder().id(owner).build()));
        when(pageRequestFactory.build(null, null)).thenReturn(PageRequest.of(0, 20));
        when(diaryViewRepository.findGroupedPage(eq(owner), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(grouped)));
        when(contentRepository.findAllById(List.of(seriesContentId))).thenReturn(List.of(series));
        when(contentMapper.contentToContentRefDto(series)).thenReturn(seriesReference);
        when(contentCardAssembler.assemble(anyCollection(), any(), any(ContentCardFieldSet.class)))
                .thenReturn(java.util.Map.of(new ContentCoordinate(ContentType.SERIES, "1396", null, null, null),
                        seriesCard));

        var result = service.getView(owner, owner, new DiaryViewFilter(null, null, null, null, null, null),
                null, null, true);

        assertThat(result.getContent()).singleElement().isInstanceOfSatisfying(DiaryViewGroupedItemDTO.class, item -> {
            assertThat(item.content().id()).isEqualTo(seriesContentId);
            assertThat(item.content().type()).isEqualTo(ContentType.SERIES);
            assertThat(item.ratingContentId()).isEqualTo(seasonContentId);
            assertThat(item.groupType()).isEqualTo(ContentType.SEASON);
        });
    }

    @Test
    void shouldKeepGroupedDiaryReadableWhenSeriesBackdropIsMissing() {
        UUID owner = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 11, 4);
        Instant now = Instant.now();
        var key = new DiaryViewRepository.DiaryViewKey("PERSISTED_ENTRY", entry, UUID.randomUUID(),
                ContentType.EPISODE, null, "1396", 1, 2, 1, entry, date, now, now, entry, null);
        var grouped = new DiaryViewRepository.GroupedDiaryViewKey(key, 2, ContentType.EPISODE,
                date, null, null, "1396", 1, List.of(1), 2, 90L);
        ContentCardDTO seriesCardWithoutBackdrop = new ContentCardDTO(
                null, ContentType.SERIES, "1396", null, null, null,
                "Series", null, null, null, null, null, null, null, null,
                List.of(), null, null, ContentPreviewStatus.AVAILABLE);
        when(userRepository.findById(owner)).thenReturn(Optional.of(User.builder().id(owner).build()));
        when(pageRequestFactory.build(null, null)).thenReturn(PageRequest.of(0, 20));
        when(diaryViewRepository.findGroupedPage(eq(owner), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(grouped)));
        when(contentRepository.findAllById(List.of())).thenReturn(List.of());
        when(contentCardAssembler.assemble(anyCollection(), any(), any(ContentCardFieldSet.class)))
                .thenReturn(java.util.Map.of(new ContentCoordinate(ContentType.SERIES, "1396", null, null, null),
                        seriesCardWithoutBackdrop));

        var result = service.getView(owner, owner, new DiaryViewFilter(null, null, null, null, null, null),
                null, null, true);

        assertThat(result.getContent()).singleElement().isInstanceOfSatisfying(
                DiaryViewGroupedItemDTO.class, item -> assertThat(item.backdropPath()).isNull());
    }

    @Test
    void shouldCheckVisibilityBeforeLoadingGroupedHistory() {
        UUID viewer = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        when(userRepository.findById(owner)).thenReturn(Optional.of(User.builder().id(owner).build()));
        doThrow(new AccessDeniedException("private")).when(userVisibilityService).assertCanView(viewer, owner);
        assertThatThrownBy(() -> service.getView(viewer, owner,
                new DiaryViewFilter(null, null, null, null, null, null), null, null, true))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(diaryViewRepository, diaryCompletionService);
    }
}
