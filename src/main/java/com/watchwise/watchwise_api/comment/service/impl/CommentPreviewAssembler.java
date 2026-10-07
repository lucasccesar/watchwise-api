package com.watchwise.watchwise_api.comment.service.impl;

import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.entity.Comment;
import com.watchwise.watchwise_api.comment.mapper.CommentMapper;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.comment.service.CommentPreviewData;
import com.watchwise.watchwise_api.like.service.LikeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CommentPreviewAssembler {

    private static final int MAX_RECENT_COMMENTS = 3;

    private final CommentRepository commentRepository;
    private final LikeService likeService;
    private final CommentMapper commentMapper;

    public Map<UUID, Long> countDiaryEntries(Collection<UUID> diaryEntryIds) {
        return countOnly(diaryEntryIds,
                ids -> commentRepository.countByDiaryEntryIdIn(ids).stream()
                        .collect(Collectors.toMap(CommentRepository.DiaryCommentCount::getDiaryEntryId,
                                CommentRepository.DiaryCommentCount::getCount)));
    }

    public Map<UUID, Long> countDroppedEntries(Collection<UUID> droppedEntryIds) {
        return countOnly(droppedEntryIds,
                ids -> commentRepository.countByDroppedEntryIdIn(ids).stream()
                        .collect(Collectors.toMap(CommentRepository.DroppedCommentCount::getDroppedEntryId,
                                CommentRepository.DroppedCommentCount::getCount)));
    }

    public Map<UUID, CommentPreviewData> assembleDiaryEntryPreviews(
            Collection<UUID> diaryEntryIds, UUID viewerId) {
        return assemble(diaryEntryIds,
                ids -> commentRepository.countByDiaryEntryIdIn(ids).stream()
                        .collect(Collectors.toMap(CommentRepository.DiaryCommentCount::getDiaryEntryId,
                                CommentRepository.DiaryCommentCount::getCount)),
                commentRepository::findRecentByDiaryEntryIdIn,
                comment -> comment.getDiaryEntry().getId(),
                viewerId);
    }

    public Map<UUID, CommentPreviewData> assembleDroppedEntryPreviews(
            Collection<UUID> droppedEntryIds, UUID viewerId) {
        return assemble(droppedEntryIds,
                ids -> commentRepository.countByDroppedEntryIdIn(ids).stream()
                        .collect(Collectors.toMap(CommentRepository.DroppedCommentCount::getDroppedEntryId,
                                CommentRepository.DroppedCommentCount::getCount)),
                commentRepository::findRecentByDroppedEntryIdIn,
                comment -> comment.getDroppedEntry().getId(),
                viewerId);
    }

    public Map<UUID, CommentPreviewData> assemblePickPreviews(Collection<UUID> pickIds, UUID viewerId) {
        return assemble(pickIds,
                ids -> commentRepository.countByPickIdIn(ids).stream()
                        .collect(Collectors.toMap(CommentRepository.PickCommentCount::getPickId,
                                CommentRepository.PickCommentCount::getCount)),
                commentRepository::findRecentByPickIdIn,
                comment -> comment.getPick().getId(),
                viewerId);
    }

    public Map<UUID, CommentPreviewData> assemblePicksTemplatePreviews(
            Collection<UUID> templateIds, UUID viewerId) {
        return assemble(templateIds,
                ids -> commentRepository.countByPicksTemplateIdIn(ids).stream()
                        .collect(Collectors.toMap(CommentRepository.TemplateCommentCount::getTemplateId,
                                CommentRepository.TemplateCommentCount::getCount)),
                commentRepository::findRecentByPicksTemplateIdIn,
                comment -> comment.getPicksTemplate().getId(),
                viewerId);
    }

    private Map<UUID, CommentPreviewData> assemble(
            Collection<UUID> targetIds,
            Function<Collection<UUID>, Map<UUID, Long>> countLoader,
            Function<Collection<UUID>, List<Comment>> commentsLoader,
            Function<Comment, UUID> targetIdExtractor,
            UUID viewerId) {
        if (targetIds.isEmpty()) {
            return Map.of();
        }

        Set<UUID> requestedTargetIds = new HashSet<>(targetIds);
        Map<UUID, Long> countsByTargetId = countLoader.apply(targetIds);
        Map<UUID, List<Comment>> recentCommentsByTargetId = new LinkedHashMap<>();
        for (Comment comment : commentsLoader.apply(targetIds)) {
            UUID targetId = targetIdExtractor.apply(comment);
            if (!requestedTargetIds.contains(targetId)) {
                continue;
            }

            List<Comment> targetComments = recentCommentsByTargetId.computeIfAbsent(targetId, ignored -> new ArrayList<>());
            if (targetComments.size() < MAX_RECENT_COMMENTS) {
                targetComments.add(comment);
            }
        }

        List<UUID> commentIds = recentCommentsByTargetId.values().stream()
                .flatMap(Collection::stream)
                .map(Comment::getId)
                .toList();
        Set<UUID> likedCommentIds = likeService.getLikedCommentIds(viewerId, commentIds);

        Map<UUID, CommentPreviewData> previewsByTargetId = new LinkedHashMap<>();
        for (UUID targetId : targetIds) {
            List<CommentResponseDTO> recentComments = recentCommentsByTargetId.getOrDefault(targetId, List.of()).stream()
                    .map(comment -> commentMapper.commentToResponseDto(comment, likedCommentIds.contains(comment.getId())))
                    .toList();
            previewsByTargetId.put(targetId,
                    new CommentPreviewData(countsByTargetId.getOrDefault(targetId, 0L), recentComments));
        }
        return previewsByTargetId;
    }

    private Map<UUID, Long> countOnly(
            Collection<UUID> targetIds,
            Function<Collection<UUID>, Map<UUID, Long>> countLoader) {
        if (targetIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Long> countsByTargetId = countLoader.apply(targetIds);
        Map<UUID, Long> result = new LinkedHashMap<>();
        for (UUID targetId : targetIds) {
            result.put(targetId, countsByTargetId.getOrDefault(targetId, 0L));
        }
        return result;
    }
}
