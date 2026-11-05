package com.watchwise.watchwise_api.diaryentry.service.impl;

import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
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
                date, null, "1396", 1, List.of(1), 2, 90L);
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
