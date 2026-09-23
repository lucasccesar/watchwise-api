package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.pick.dto.PickAnsweredCategoryPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickPreviewDTO;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pick.mapper.PickMapper;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateOption;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
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
public class PickPreviewAssembler {

    private final PickSelectionRepository selectionRepository;
    private final PicksTemplateCategoryRepository categoryRepository;
    private final PicksTemplateOptionRepository optionRepository;
    private final CommentRepository commentRepository;
    private final LikeService likeService;
    private final PickMapper pickMapper;
    private final PickTargetService targetService;
    private final UserMapper userMapper;

    public Page<PickPreviewDTO> assemblePage(Page<Pick> picks, UUID viewerId) {
        if (picks.isEmpty()) {
            return picks.map(pick -> null);
        }
        Map<UUID, PickPreviewDTO> previews = assemble(picks.getContent(), viewerId);
        return picks.map(pick -> previews.get(pick.getId()));
    }

    public Map<UUID, PickPreviewDTO> assemble(Collection<Pick> picks, UUID viewerId) {
        if (picks.isEmpty()) {
            return Map.of();
        }

        List<UUID> pickIds = picks.stream().map(Pick::getId).toList();
        Map<UUID, List<PickSelection>> selectionsByPickId = selectionRepository.findByPickIdIn(pickIds).stream()
                .collect(Collectors.groupingBy(selection -> selection.getPick().getId()));
        Map<UUID, List<PicksTemplateCategory>> categoriesByTemplateId = loadCategoriesByTemplateId(picks);
        List<UUID> fixedCategoryIds = categoriesByTemplateId.values().stream().flatMap(Collection::stream)
                .filter(category -> category.getOptionMode() == PickCategoryOptionMode.FIXED)
                .map(PicksTemplateCategory::getId).toList();
        Map<UUID, List<PicksTemplateOption>> optionsByCategory = fixedCategoryIds.isEmpty() ? Map.of()
                : optionRepository.findByCategoryIdIn(fixedCategoryIds).stream()
                .collect(Collectors.groupingBy(option -> option.getCategory().getId()));
        Map<UUID, Long> commentsByPickId = commentRepository.countByPickIdIn(pickIds).stream()
                .collect(Collectors.toMap(CommentRepository.PickCommentCount::getPickId,
                        CommentRepository.PickCommentCount::getCount));
        Set<UUID> likedPickIds = likeService.getLikedPickIds(viewerId, pickIds);

        return picks.stream().collect(Collectors.toMap(Pick::getId, pick -> {
            List<PicksTemplateCategory> categories = categoriesByTemplateId
                    .getOrDefault(pick.getPicksTemplate().getId(), List.of());
            Map<UUID, PickSelection> selectionByCategory = selectionsByPickId
                    .getOrDefault(pick.getId(), List.of()).stream()
                    .collect(Collectors.toMap(selection -> selection.getCategory().getId(), Function.identity()));
            List<PickAnsweredCategoryPreviewDTO> answered = categories.stream()
                    .filter(category -> selectionByCategory.containsKey(category.getId()))
                    .limit(5)
                    .map(category -> {
                        PickSelection selection = selectionByCategory.get(category.getId());
                        boolean valid = targetService.isStructurallyValid(category, selection,
                                optionsByCategory.getOrDefault(category.getId(), List.of()));
                        return new PickAnsweredCategoryPreviewDTO(category.getId(), category.getName(), category.getGroup(),
                                category.getDisplayOrder(), pickMapper.pickSelectionToSearchDto(selection), valid);
                    }).toList();
            return new PickPreviewDTO(pick.getId(), userMapper.userToUserPreviewDto(pick.getUser()), pick.getVisibility(),
                    pick.getCreatedAt(), pick.getLikesCount() == null ? 0 : pick.getLikesCount(),
                    commentsByPickId.getOrDefault(pick.getId(), 0L), likedPickIds.contains(pick.getId()), answered);
        }, (first, ignored) -> first));
    }

    private Map<UUID, List<PicksTemplateCategory>> loadCategoriesByTemplateId(Collection<Pick> picks) {
        List<UUID> templateIds = picks.stream().map(pick -> pick.getPicksTemplate().getId()).distinct().toList();
        List<PicksTemplateCategory> loaded = categoryRepository.findByPicksTemplateIdInOrderByDisplayOrder(templateIds);
        if (loaded.isEmpty()) {
            loaded = picks.stream().map(Pick::getPicksTemplate).distinct()
                    .flatMap(template -> categoryRepository.findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(template.getId()).stream())
                    .toList();
        }
        return loaded.stream().collect(Collectors.groupingBy(category -> category.getPicksTemplate().getId()));
    }
}
