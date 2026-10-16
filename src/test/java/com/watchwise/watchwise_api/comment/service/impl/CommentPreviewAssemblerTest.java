package com.watchwise.watchwise_api.comment.service.impl;

import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.mapper.CommentMapper;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.comment.service.CommentPreviewData;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.dropped.entity.DroppedEntry;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentPreviewAssemblerTest {

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private LikeService likeService;

    @Mock
    private CommentMapper commentMapper;

    @InjectMocks
    private CommentPreviewAssembler assembler;

    @Test
    void returnsEmptyMapsWithoutCallingDependenciesWhenTargetIdsAreEmpty() {
        UUID viewerId = UUID.randomUUID();

        assertThat(assembler.assembleDiaryEntryPreviews(List.of(), viewerId)).isEmpty();
        assertThat(assembler.assembleDroppedEntryPreviews(List.of(), viewerId)).isEmpty();
        assertThat(assembler.assemblePickPreviews(List.of(), viewerId)).isEmpty();
        assertThat(assembler.assemblePicksTemplatePreviews(List.of(), viewerId)).isEmpty();

        verifyNoInteractions(commentRepository, likeService, commentMapper);
    }

    @Test
    void countsDiaryEntriesWithoutLoadingRecentCommentsOrLikes() {
        UUID viewerId = UUID.randomUUID();
        UUID firstTargetId = UUID.randomUUID();
        UUID secondTargetId = UUID.randomUUID();
        List<UUID> targetIds = List.of(firstTargetId, secondTargetId);

        CommentRepository.DiaryCommentCount firstCount = diaryCount(firstTargetId, 3);
        when(commentRepository.countVisibleDiaryComments(viewerId, targetIds)).thenReturn(List.of(firstCount));

        assertThat(assembler.countDiaryEntries(targetIds, viewerId))
                .containsEntry(firstTargetId, 3L)
                .containsEntry(secondTargetId, 0L);

        verify(commentRepository).countVisibleDiaryComments(viewerId, targetIds);
        verify(commentRepository, never()).findRecentByDiaryEntryIdIn(any());
        verifyNoInteractions(likeService, commentMapper);
    }

    @Test
    void countsDroppedEntriesWithoutLoadingRecentCommentsOrLikes() {
        UUID viewerId = UUID.randomUUID();
        UUID firstTargetId = UUID.randomUUID();
        List<UUID> targetIds = List.of(firstTargetId);

        CommentRepository.DroppedCommentCount firstCount = droppedCount(firstTargetId, 4);
        when(commentRepository.countVisibleDroppedComments(viewerId, targetIds)).thenReturn(List.of(firstCount));

        assertThat(assembler.countDroppedEntries(targetIds, viewerId))
                .containsEntry(firstTargetId, 4L);

        verify(commentRepository).countVisibleDroppedComments(viewerId, targetIds);
        verify(commentRepository, never()).findRecentByDroppedEntryIdIn(any());
        verifyNoInteractions(likeService, commentMapper);
    }

    @Test
    void returnsZeroCountAndEmptyCommentsForTargetsWithoutComments() {
        UUID viewerId = UUID.randomUUID();
        UUID diaryEntryId = UUID.randomUUID();
        UUID droppedEntryId = UUID.randomUUID();
        UUID pickId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();

        when(commentRepository.countVisibleDiaryComments(viewerId, List.of(diaryEntryId))).thenReturn(List.of());
        when(commentRepository.findRecentVisibleByDiaryEntryIds(viewerId, List.of(diaryEntryId))).thenReturn(List.of());
        when(commentRepository.countVisibleDroppedComments(viewerId, List.of(droppedEntryId))).thenReturn(List.of());
        when(commentRepository.findRecentVisibleByDroppedEntryIds(viewerId, List.of(droppedEntryId))).thenReturn(List.of());
        when(commentRepository.countVisiblePickComments(viewerId, List.of(pickId))).thenReturn(List.of());
        when(commentRepository.findRecentVisibleByPickIds(viewerId, List.of(pickId))).thenReturn(List.of());
        when(commentRepository.countVisibleTemplateComments(viewerId, List.of(templateId))).thenReturn(List.of());
        when(commentRepository.findRecentVisibleByTemplateIds(viewerId, List.of(templateId))).thenReturn(List.of());
        when(likeService.getLikedCommentIds(viewerId, List.of())).thenReturn(Set.of());

        assertThat(assembler.assembleDiaryEntryPreviews(List.of(diaryEntryId), viewerId))
                .containsEntry(diaryEntryId, new CommentPreviewData(0, List.of()));
        assertThat(assembler.assembleDroppedEntryPreviews(List.of(droppedEntryId), viewerId))
                .containsEntry(droppedEntryId, new CommentPreviewData(0, List.of()));
        assertThat(assembler.assemblePickPreviews(List.of(pickId), viewerId))
                .containsEntry(pickId, new CommentPreviewData(0, List.of()));
        assertThat(assembler.assemblePicksTemplatePreviews(List.of(templateId), viewerId))
                .containsEntry(templateId, new CommentPreviewData(0, List.of()));

        verify(likeService, times(4)).getLikedCommentIds(viewerId, List.of());
        verifyNoMoreInteractions(commentRepository, likeService, commentMapper);
    }

    @Test
    void assemblesDiaryEntryPreviewsWithBoundedOrderedIsolatedAndViewerAwareComments() {
        UUID viewerId = UUID.randomUUID();
        UUID firstTargetId = UUID.randomUUID();
        UUID secondTargetId = UUID.randomUUID();
        UUID emptyTargetId = UUID.randomUUID();
        List<UUID> targetIds = List.of(firstTargetId, secondTargetId, emptyTargetId);
        List<Comment> comments = List.of(
                diaryComment(UUID.randomUUID(), firstTargetId),
                diaryComment(UUID.randomUUID(), firstTargetId),
                diaryComment(UUID.randomUUID(), firstTargetId),
                diaryComment(UUID.randomUUID(), firstTargetId),
                diaryComment(UUID.randomUUID(), secondTargetId));
        UUID likedCommentId = comments.get(1).getId();
        UUID secondLikedCommentId = comments.get(4).getId();
        Set<UUID> likedCommentIds = Set.of(likedCommentId, secondLikedCommentId);

        CommentRepository.DiaryCommentCount firstCount = diaryCount(firstTargetId, 8);
        CommentRepository.DiaryCommentCount secondCount = diaryCount(secondTargetId, 1);
        when(commentRepository.countVisibleDiaryComments(viewerId, targetIds)).thenReturn(List.of(firstCount, secondCount));
        when(commentRepository.findRecentVisibleByDiaryEntryIds(viewerId, targetIds)).thenReturn(comments);
        stubCommentDtos(previewComments(comments), likedCommentIds);
        when(likeService.getLikedCommentIds(viewerId,
                List.of(comments.get(0).getId(), comments.get(1).getId(), comments.get(2).getId(), comments.get(4).getId())))
                .thenReturn(likedCommentIds);

        Map<UUID, CommentPreviewData> result = assembler.assembleDiaryEntryPreviews(targetIds, viewerId);

        assertPreview(result, firstTargetId, 8,
                List.of(comments.get(0), comments.get(1), comments.get(2)), List.of(false, true, false));
        assertPreview(result, secondTargetId, 1, List.of(comments.get(4)), List.of(true));
        assertPreview(result, emptyTargetId, 0, List.of(), List.of());
        verify(commentRepository).countVisibleDiaryComments(viewerId, targetIds);
        verify(commentRepository).findRecentVisibleByDiaryEntryIds(viewerId, targetIds);
        verify(commentRepository, never()).countVisibleDroppedComments(eq(viewerId), any());
        verify(commentRepository, never()).findRecentVisibleByDroppedEntryIds(eq(viewerId), any());
        verify(commentRepository, never()).countVisiblePickComments(eq(viewerId), any());
        verify(commentRepository, never()).findRecentVisibleByPickIds(eq(viewerId), any());
        verify(commentRepository, never()).countVisibleTemplateComments(eq(viewerId), any());
        verify(commentRepository, never()).findRecentVisibleByTemplateIds(eq(viewerId), any());
        verify(likeService).getLikedCommentIds(viewerId,
                List.of(comments.get(0).getId(), comments.get(1).getId(), comments.get(2).getId(), comments.get(4).getId()));
        verifyNoMoreInteractions(commentRepository, likeService);
    }

    @Test
    void assemblesDiaryAndListPreviewsFromViewerFilteredCountsAndRecentRows() {
        UUID viewerId = UUID.randomUUID();
        UUID diaryId = UUID.randomUUID();
        UUID listId = UUID.randomUUID();
        Comment allowedDiaryComment = diaryComment(UUID.randomUUID(), diaryId);
        Comment allowedListComment = listComment(UUID.randomUUID(), listId);
        CommentRepository.DiaryCommentCount diaryVisibleCount = diaryCount(diaryId, 1);
        CommentRepository.ListCommentCount listVisibleCount = listCount(listId, 1);

        when(commentRepository.countVisibleDiaryComments(viewerId, List.of(diaryId)))
                .thenReturn(List.of(diaryVisibleCount));
        when(commentRepository.findRecentVisibleByDiaryEntryIds(viewerId, List.of(diaryId)))
                .thenReturn(List.of(allowedDiaryComment));
        when(commentRepository.countVisibleListComments(viewerId, List.of(listId)))
                .thenReturn(List.of(listVisibleCount));
        when(commentRepository.findRecentVisibleByListIds(viewerId, List.of(listId)))
                .thenReturn(List.of(allowedListComment));
        when(likeService.getLikedCommentIds(viewerId, List.of(allowedDiaryComment.getId())))
                .thenReturn(Set.of());
        when(likeService.getLikedCommentIds(viewerId, List.of(allowedListComment.getId())))
                .thenReturn(Set.of());
        when(commentMapper.commentToResponseDto(allowedDiaryComment, false))
                .thenReturn(commentDto(allowedDiaryComment.getId(), false));
        when(commentMapper.commentToResponseDto(allowedListComment, false))
                .thenReturn(commentDto(allowedListComment.getId(), false));

        Map<UUID, CommentPreviewData> diaryResult = assembler.assembleDiaryEntryPreviews(List.of(diaryId), viewerId);
        Map<UUID, CommentPreviewData> listResult = assembler.assembleListPreviews(List.of(listId), viewerId);

        assertPreview(diaryResult, diaryId, 1, List.of(allowedDiaryComment), List.of(false));
        assertPreview(listResult, listId, 1, List.of(allowedListComment), List.of(false));
        verify(commentRepository).countVisibleDiaryComments(viewerId, List.of(diaryId));
        verify(commentRepository).findRecentVisibleByDiaryEntryIds(viewerId, List.of(diaryId));
        verify(commentRepository).countVisibleListComments(viewerId, List.of(listId));
        verify(commentRepository).findRecentVisibleByListIds(viewerId, List.of(listId));
    }

    @Test
    void assemblesDroppedEntryPreviewsWithBoundedOrderedIsolatedAndViewerAwareComments() {
        UUID viewerId = UUID.randomUUID();
        UUID firstTargetId = UUID.randomUUID();
        UUID secondTargetId = UUID.randomUUID();
        UUID emptyTargetId = UUID.randomUUID();
        List<UUID> targetIds = List.of(firstTargetId, secondTargetId, emptyTargetId);
        List<Comment> comments = List.of(
                droppedComment(UUID.randomUUID(), firstTargetId),
                droppedComment(UUID.randomUUID(), firstTargetId),
                droppedComment(UUID.randomUUID(), firstTargetId),
                droppedComment(UUID.randomUUID(), firstTargetId),
                droppedComment(UUID.randomUUID(), secondTargetId));
        UUID likedCommentId = comments.get(1).getId();
        UUID secondLikedCommentId = comments.get(4).getId();
        Set<UUID> likedCommentIds = Set.of(likedCommentId, secondLikedCommentId);

        CommentRepository.DroppedCommentCount firstCount = droppedCount(firstTargetId, 8);
        CommentRepository.DroppedCommentCount secondCount = droppedCount(secondTargetId, 1);
        when(commentRepository.countVisibleDroppedComments(viewerId, targetIds)).thenReturn(List.of(firstCount, secondCount));
        when(commentRepository.findRecentVisibleByDroppedEntryIds(viewerId, targetIds)).thenReturn(comments);
        stubCommentDtos(previewComments(comments), likedCommentIds);
        when(likeService.getLikedCommentIds(viewerId,
                List.of(comments.get(0).getId(), comments.get(1).getId(), comments.get(2).getId(), comments.get(4).getId())))
                .thenReturn(likedCommentIds);

        Map<UUID, CommentPreviewData> result = assembler.assembleDroppedEntryPreviews(targetIds, viewerId);

        assertPreview(result, firstTargetId, 8,
                List.of(comments.get(0), comments.get(1), comments.get(2)), List.of(false, true, false));
        assertPreview(result, secondTargetId, 1, List.of(comments.get(4)), List.of(true));
        assertPreview(result, emptyTargetId, 0, List.of(), List.of());
        verify(commentRepository).countVisibleDroppedComments(viewerId, targetIds);
        verify(commentRepository).findRecentVisibleByDroppedEntryIds(viewerId, targetIds);
        verify(commentRepository, never()).countVisibleDiaryComments(eq(viewerId), any());
        verify(commentRepository, never()).findRecentVisibleByDiaryEntryIds(eq(viewerId), any());
        verify(commentRepository, never()).countVisiblePickComments(eq(viewerId), any());
        verify(commentRepository, never()).findRecentVisibleByPickIds(eq(viewerId), any());
        verify(commentRepository, never()).countVisibleTemplateComments(eq(viewerId), any());
        verify(commentRepository, never()).findRecentVisibleByTemplateIds(eq(viewerId), any());
        verify(likeService).getLikedCommentIds(viewerId,
                List.of(comments.get(0).getId(), comments.get(1).getId(), comments.get(2).getId(), comments.get(4).getId()));
        verifyNoMoreInteractions(commentRepository, likeService);
    }

    @Test
    void assemblesPickPreviewsWithBoundedOrderedIsolatedAndViewerAwareComments() {
        UUID viewerId = UUID.randomUUID();
        UUID firstTargetId = UUID.randomUUID();
        UUID secondTargetId = UUID.randomUUID();
        UUID emptyTargetId = UUID.randomUUID();
        List<UUID> targetIds = List.of(firstTargetId, secondTargetId, emptyTargetId);
        List<Comment> comments = List.of(
                pickComment(UUID.randomUUID(), firstTargetId),
                pickComment(UUID.randomUUID(), firstTargetId),
                pickComment(UUID.randomUUID(), firstTargetId),
                pickComment(UUID.randomUUID(), firstTargetId),
                pickComment(UUID.randomUUID(), secondTargetId));
        UUID likedCommentId = comments.get(1).getId();
        UUID secondLikedCommentId = comments.get(4).getId();
        Set<UUID> likedCommentIds = Set.of(likedCommentId, secondLikedCommentId);

        CommentRepository.PickCommentCount firstCount = pickCount(firstTargetId, 8);
        CommentRepository.PickCommentCount secondCount = pickCount(secondTargetId, 1);
        when(commentRepository.countVisiblePickComments(viewerId, targetIds)).thenReturn(List.of(firstCount, secondCount));
        when(commentRepository.findRecentVisibleByPickIds(viewerId, targetIds)).thenReturn(comments);
        stubCommentDtos(previewComments(comments), likedCommentIds);
        when(likeService.getLikedCommentIds(viewerId,
                List.of(comments.get(0).getId(), comments.get(1).getId(), comments.get(2).getId(), comments.get(4).getId())))
                .thenReturn(likedCommentIds);

        Map<UUID, CommentPreviewData> result = assembler.assemblePickPreviews(targetIds, viewerId);

        assertPreview(result, firstTargetId, 8,
                List.of(comments.get(0), comments.get(1), comments.get(2)), List.of(false, true, false));
        assertPreview(result, secondTargetId, 1, List.of(comments.get(4)), List.of(true));
        assertPreview(result, emptyTargetId, 0, List.of(), List.of());
        verify(commentRepository).countVisiblePickComments(viewerId, targetIds);
        verify(commentRepository).findRecentVisibleByPickIds(viewerId, targetIds);
        verify(commentRepository, never()).countVisibleDiaryComments(eq(viewerId), any());
        verify(commentRepository, never()).findRecentVisibleByDiaryEntryIds(eq(viewerId), any());
        verify(commentRepository, never()).countVisibleDroppedComments(eq(viewerId), any());
        verify(commentRepository, never()).findRecentVisibleByDroppedEntryIds(eq(viewerId), any());
        verify(commentRepository, never()).countVisibleTemplateComments(eq(viewerId), any());
        verify(commentRepository, never()).findRecentVisibleByTemplateIds(eq(viewerId), any());
        verify(likeService).getLikedCommentIds(viewerId,
                List.of(comments.get(0).getId(), comments.get(1).getId(), comments.get(2).getId(), comments.get(4).getId()));
        verifyNoMoreInteractions(commentRepository, likeService);
    }

    @Test
    void assemblesPicksTemplatePreviewsWithBoundedOrderedIsolatedAndViewerAwareComments() {
        UUID viewerId = UUID.randomUUID();
        UUID firstTargetId = UUID.randomUUID();
        UUID secondTargetId = UUID.randomUUID();
        UUID emptyTargetId = UUID.randomUUID();
        List<UUID> targetIds = List.of(firstTargetId, secondTargetId, emptyTargetId);
        List<Comment> comments = List.of(
                templateComment(UUID.randomUUID(), firstTargetId),
                templateComment(UUID.randomUUID(), firstTargetId),
                templateComment(UUID.randomUUID(), firstTargetId),
                templateComment(UUID.randomUUID(), firstTargetId),
                templateComment(UUID.randomUUID(), secondTargetId));
        UUID likedCommentId = comments.get(1).getId();
        UUID secondLikedCommentId = comments.get(4).getId();
        Set<UUID> likedCommentIds = Set.of(likedCommentId, secondLikedCommentId);

        CommentRepository.TemplateCommentCount firstCount = templateCount(firstTargetId, 8);
        CommentRepository.TemplateCommentCount secondCount = templateCount(secondTargetId, 1);
        when(commentRepository.countVisibleTemplateComments(viewerId, targetIds)).thenReturn(List.of(firstCount, secondCount));
        when(commentRepository.findRecentVisibleByTemplateIds(viewerId, targetIds)).thenReturn(comments);
        stubCommentDtos(previewComments(comments), likedCommentIds);
        when(likeService.getLikedCommentIds(viewerId,
                List.of(comments.get(0).getId(), comments.get(1).getId(), comments.get(2).getId(), comments.get(4).getId())))
                .thenReturn(likedCommentIds);

        Map<UUID, CommentPreviewData> result = assembler.assemblePicksTemplatePreviews(targetIds, viewerId);

        assertPreview(result, firstTargetId, 8,
                List.of(comments.get(0), comments.get(1), comments.get(2)), List.of(false, true, false));
        assertPreview(result, secondTargetId, 1, List.of(comments.get(4)), List.of(true));
        assertPreview(result, emptyTargetId, 0, List.of(), List.of());
        verify(commentRepository).countVisibleTemplateComments(viewerId, targetIds);
        verify(commentRepository).findRecentVisibleByTemplateIds(viewerId, targetIds);
        verify(commentRepository, never()).countVisibleDiaryComments(eq(viewerId), any());
        verify(commentRepository, never()).findRecentVisibleByDiaryEntryIds(eq(viewerId), any());
        verify(commentRepository, never()).countVisibleDroppedComments(eq(viewerId), any());
        verify(commentRepository, never()).findRecentVisibleByDroppedEntryIds(eq(viewerId), any());
        verify(commentRepository, never()).countVisiblePickComments(eq(viewerId), any());
        verify(commentRepository, never()).findRecentVisibleByPickIds(eq(viewerId), any());
        verify(likeService).getLikedCommentIds(viewerId,
                List.of(comments.get(0).getId(), comments.get(1).getId(), comments.get(2).getId(), comments.get(4).getId()));
        verifyNoMoreInteractions(commentRepository, likeService);
    }

    private void assertPreview(Map<UUID, CommentPreviewData> previews, UUID targetId, long commentsCount,
            List<Comment> expectedComments, List<Boolean> expectedLikedByViewer) {
        CommentPreviewData preview = previews.get(targetId);

        assertThat(preview.commentsCount()).isEqualTo(commentsCount);
        assertThat(preview.recentComments()).extracting(CommentResponseDTO::id)
                .containsExactlyElementsOf(expectedComments.stream().map(Comment::getId).toList());
        assertThat(preview.recentComments()).extracting(CommentResponseDTO::likedByMe)
                .containsExactlyElementsOf(expectedLikedByViewer);
        assertThat(preview.recentComments()).hasSizeLessThanOrEqualTo(3);
    }

    private void stubCommentDtos(List<Comment> comments, Set<UUID> likedCommentIds) {
        for (Comment comment : comments) {
            boolean likedByViewer = likedCommentIds.contains(comment.getId());
            when(commentMapper.commentToResponseDto(comment, likedByViewer))
                    .thenReturn(commentDto(comment.getId(), likedByViewer));
        }
    }

    private List<Comment> previewComments(List<Comment> comments) {
        return List.of(comments.get(0), comments.get(1), comments.get(2), comments.get(4));
    }

    private CommentResponseDTO commentDto(UUID id, boolean likedByViewer) {
        return new CommentResponseDTO(id, null, null, null, null, null, null, null, null,
                "comment", false, LocalDateTime.MIN, LocalDateTime.MIN, 0, likedByViewer);
    }

    private Comment diaryComment(UUID commentId, UUID targetId) {
        return Comment.builder().id(commentId).diaryEntry(DiaryEntry.builder().id(targetId).build()).build();
    }

    private Comment listComment(UUID commentId, UUID targetId) {
        return Comment.builder().id(commentId).list(UserList.builder().id(targetId).build()).build();
    }

    private Comment droppedComment(UUID commentId, UUID targetId) {
        return Comment.builder().id(commentId).droppedEntry(DroppedEntry.builder().id(targetId).build()).build();
    }

    private Comment pickComment(UUID commentId, UUID targetId) {
        return Comment.builder().id(commentId).pick(Pick.builder().id(targetId).build()).build();
    }

    private Comment templateComment(UUID commentId, UUID targetId) {
        return Comment.builder().id(commentId).picksTemplate(PicksTemplate.builder().id(targetId).build()).build();
    }

    private CommentRepository.DiaryCommentCount diaryCount(UUID targetId, long count) {
        CommentRepository.DiaryCommentCount row = org.mockito.Mockito.mock(CommentRepository.DiaryCommentCount.class);
        when(row.getDiaryEntryId()).thenReturn(targetId);
        when(row.getCount()).thenReturn(count);
        return row;
    }

    private CommentRepository.ListCommentCount listCount(UUID targetId, long count) {
        CommentRepository.ListCommentCount row = org.mockito.Mockito.mock(CommentRepository.ListCommentCount.class);
        when(row.getListId()).thenReturn(targetId);
        when(row.getCount()).thenReturn(count);
        return row;
    }

    private CommentRepository.DroppedCommentCount droppedCount(UUID targetId, long count) {
        CommentRepository.DroppedCommentCount row = org.mockito.Mockito.mock(CommentRepository.DroppedCommentCount.class);
        when(row.getDroppedEntryId()).thenReturn(targetId);
        when(row.getCount()).thenReturn(count);
        return row;
    }

    private CommentRepository.PickCommentCount pickCount(UUID targetId, long count) {
        CommentRepository.PickCommentCount row = org.mockito.Mockito.mock(CommentRepository.PickCommentCount.class);
        when(row.getPickId()).thenReturn(targetId);
        when(row.getCount()).thenReturn(count);
        return row;
    }

    private CommentRepository.TemplateCommentCount templateCount(UUID targetId, long count) {
        CommentRepository.TemplateCommentCount row = org.mockito.Mockito.mock(CommentRepository.TemplateCommentCount.class);
        when(row.getTemplateId()).thenReturn(targetId);
        when(row.getCount()).thenReturn(count);
        return row;
    }
}
