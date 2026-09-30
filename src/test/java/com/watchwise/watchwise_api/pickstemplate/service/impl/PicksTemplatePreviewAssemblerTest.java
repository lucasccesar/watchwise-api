package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.comment.service.CommentPreviewData;
import com.watchwise.watchwise_api.comment.service.impl.CommentPreviewAssembler;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PicksTemplatePreviewAssemblerTest {

    @Mock PicksTemplateCategoryRepository categoryRepository;
    @Mock PickRepository pickRepository;
    @Mock CommentRepository commentRepository;
    @Mock CommentPreviewAssembler commentPreviewAssembler;
    @Mock LikeService likeService;
    @Mock UserMapper userMapper;
    @InjectMocks PicksTemplatePreviewAssembler assembler;

    @Test
    void assemblesFeedTemplatePreviewWithOwnRecentCommentsAndCountFromSharedAssembler() {
        UUID viewerId = UUID.randomUUID();
        PicksTemplate template = buildTemplate();
        PicksTemplate other = buildTemplate();
        CommentResponseDTO comment = new CommentResponseDTO(UUID.randomUUID(), null, null, null, null, null, null,
                template.getId(), null, "nice", false, LocalDateTime.now(), LocalDateTime.now(), 0, false);
        List<UUID> ids = List.of(template.getId(), other.getId());
        stubTemplateSources(viewerId, ids);
        when(commentPreviewAssembler.assemblePicksTemplatePreviews(ids, viewerId)).thenReturn(Map.of(
                template.getId(), new CommentPreviewData(9, List.of(comment)),
                other.getId(), new CommentPreviewData(0, List.of())));

        Map<UUID, PicksTemplatePreviewDTO> result = assembler.assembleForFeed(List.of(template, other), viewerId);

        assertThat(result.get(template.getId()).commentsCount()).isEqualTo(9);
        assertThat(result.get(template.getId()).recentComments()).containsExactly(comment);
        assertThat(result.get(other.getId()).commentsCount()).isZero();
        assertThat(result.get(other.getId()).recentComments()).isEmpty();
        verify(commentRepository, never()).countByPicksTemplateIdIn(any());
    }

    @Test
    void returnsEmptyRecentCommentsWithoutLoadingThemWhenAssemblingOutsideTheFeed() {
        UUID viewerId = UUID.randomUUID();
        PicksTemplate template = buildTemplate();
        List<UUID> ids = List.of(template.getId());
        stubTemplateSources(viewerId, ids);
        when(commentRepository.countByPicksTemplateIdIn(ids)).thenReturn(List.of());

        PicksTemplatePreviewDTO preview = assembler.assembleOne(template, viewerId);

        assertThat(preview.recentComments()).isEmpty();
        verify(commentPreviewAssembler, never()).assemblePicksTemplatePreviews(any(), any());
    }

    private void stubTemplateSources(UUID viewerId, List<UUID> ids) {
        when(categoryRepository.findByPicksTemplateIdInOrderByDisplayOrder(ids)).thenReturn(List.of());
        when(pickRepository.countVisibleByTemplateIds(viewerId, ids)).thenReturn(List.of());
        when(likeService.getLikedPicksTemplateIds(viewerId, ids)).thenReturn(Set.of());
        when(pickRepository.countByUserIdAndTemplateIds(viewerId, ids)).thenReturn(List.of());
        when(pickRepository.findLatestByUserIdAndTemplateIds(viewerId, ids)).thenReturn(List.of());
    }

    private PicksTemplate buildTemplate() {
        return PicksTemplate.builder().id(UUID.randomUUID()).origin(PickOrigin.COMMUNITY).name("Awards")
                .createdAt(LocalDateTime.now()).likesCount(1).build();
    }
}
