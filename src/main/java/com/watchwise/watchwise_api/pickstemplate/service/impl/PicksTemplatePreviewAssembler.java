package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.comment.service.CommentPreviewData;
import com.watchwise.watchwise_api.comment.service.impl.CommentPreviewAssembler;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PicksTemplatePreviewAssembler {
    private final PicksTemplateCategoryRepository categoryRepository;
    private final PickRepository pickRepository;
    private final CommentRepository commentRepository;
    private final CommentPreviewAssembler commentPreviewAssembler;
    private final LikeService likeService;
    private final UserMapper userMapper;

    public PicksTemplatePreviewDTO assembleOne(PicksTemplate template, UUID viewerId) {
        return assemble(List.of(template), viewerId).get(template.getId());
    }

    public Page<PicksTemplatePreviewDTO> assemblePage(Page<PicksTemplate> templates, UUID viewerId) {
        Map<UUID, PicksTemplatePreviewDTO> previews = assemble(templates.getContent(), viewerId);
        return templates.map(template -> previews.get(template.getId()));
    }

    public Map<UUID, PicksTemplatePreviewDTO> assemble(Collection<PicksTemplate> templates, UUID viewerId) {
        return assemble(templates, viewerId, null);
    }

    public Map<UUID, PicksTemplatePreviewDTO> assembleForFeed(Collection<PicksTemplate> templates, UUID viewerId) {
        if (templates.isEmpty()) {
            return Map.of();
        }
        List<UUID> templateIds = templates.stream().map(PicksTemplate::getId).toList();
        return assemble(templates, viewerId, commentPreviewAssembler.assemblePicksTemplatePreviews(templateIds, viewerId));
    }

    private Map<UUID, PicksTemplatePreviewDTO> assemble(Collection<PicksTemplate> templates, UUID viewerId,
            Map<UUID, CommentPreviewData> commentPreviews) {
        if (templates.isEmpty()) {
            return Map.of();
        }

        List<UUID> templateIds = templates.stream().map(PicksTemplate::getId).toList();
        Map<UUID, List<PicksTemplateCategory>> categoriesByTemplate = categoryRepository
                .findByPicksTemplateIdInOrderByDisplayOrder(templateIds).stream()
                .collect(Collectors.groupingBy(category -> category.getPicksTemplate().getId()));
        Map<UUID, Long> visiblePickCounts = pickRepository.countVisibleByTemplateIds(viewerId, templateIds).stream()
                .collect(Collectors.toMap(PickRepository.VisiblePickCount::getTemplateId, PickRepository.VisiblePickCount::getCount));
        Map<UUID, Long> commentsCounts = commentPreviews != null ? Map.of()
                : commentRepository.countByPicksTemplateIdIn(templateIds).stream()
                .collect(Collectors.toMap(CommentRepository.TemplateCommentCount::getTemplateId, CommentRepository.TemplateCommentCount::getCount));
        Set<UUID> likedTemplateIds = likeService.getLikedPicksTemplateIds(viewerId, templateIds);
        Map<UUID, Long> ownPickCounts = pickRepository.countByUserIdAndTemplateIds(viewerId, templateIds).stream()
                .collect(Collectors.toMap(PickRepository.OwnPickCount::getTemplateId, PickRepository.OwnPickCount::getCount));
        Map<UUID, UUID> latestOwnPickIds = pickRepository.findLatestByUserIdAndTemplateIds(viewerId, templateIds).stream()
                .collect(Collectors.toMap(PickRepository.LatestOwnPick::getTemplateId, PickRepository.LatestOwnPick::getPickId));

        return templates.stream().collect(Collectors.toMap(PicksTemplate::getId, template -> {
            List<PicksTemplateCategory> categories = categoriesByTemplate.getOrDefault(template.getId(), List.of());
            CommentPreviewData commentPreview = commentPreviews == null ? null : commentPreviews.get(template.getId());
            long commentsCount = commentPreview != null ? commentPreview.commentsCount()
                    : commentsCounts.getOrDefault(template.getId(), 0L);
            List<CommentResponseDTO> recentComments = commentPreview != null ? commentPreview.recentComments()
                    : List.of();
            return new PicksTemplatePreviewDTO(
                    template.getId(),
                    template.getCreator() == null ? null : userMapper.userToUserPreviewDto(template.getCreator()),
                    template.getOrigin(),
                    template.getName(),
                    template.getDescription(),
                    template.getCoverImage(),
                    template.getCreatedAt(),
                    visiblePickCounts.getOrDefault(template.getId(), 0L),
                    categories.size(),
                    categories.stream().map(PicksTemplateCategory::getName).toList(),
                    template.getLikesCount() == null ? 0 : template.getLikesCount(),
                    commentsCount,
                    likedTemplateIds.contains(template.getId()),
                    ownPickCounts.getOrDefault(template.getId(), 0L),
                    latestOwnPickIds.get(template.getId()),
                    recentComments);
        }, (first, ignored) -> first));
    }
}
