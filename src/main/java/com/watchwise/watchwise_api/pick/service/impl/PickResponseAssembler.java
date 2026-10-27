package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.pick.dto.PickProgress;
import com.watchwise.watchwise_api.pick.dto.PickResponseDTO;
import com.watchwise.watchwise_api.pick.dto.PickSelectionDTO;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pick.mapper.PickMapper;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.service.impl.PicksTemplatePreviewAssembler;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
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
public class PickResponseAssembler {
    private final PickSelectionRepository selectionRepository;
    private final PicksTemplateCategoryRepository categoryRepository;
    private final PicksTemplatePreviewAssembler templatePreviewAssembler;
    private final CommentRepository commentRepository;
    private final LikeService likeService;
    private final PickMapper pickMapper;
    private final PickTargetService targetService;

    public PickResponseDTO assemble(Pick pick, UUID viewerId) {
        return assemble(List.of(pick), viewerId).get(pick.getId());
    }

    public Map<UUID, PickResponseDTO> assemble(Collection<Pick> picks, UUID viewerId) {
        if (picks.isEmpty()) {
            return Map.of();
        }

        List<Pick> pickList = List.copyOf(picks);
        List<UUID> pickIds = pickList.stream().map(Pick::getId).toList();
        Map<UUID, List<PickSelection>> selectionsByPickId = selectionRepository.findByPickIdIn(pickIds).stream()
                .collect(Collectors.groupingBy(selection -> selection.getPick().getId()));
        Map<UUID, List<PicksTemplateCategory>> categoriesByTemplateId = loadCategoriesByTemplateId(pickList);
        Map<UUID, PicksTemplatePreviewDTO> templatePreviews = templatePreviewAssembler.assemble(
                pickList.stream().map(Pick::getPicksTemplate)
                        .collect(Collectors.toMap(PicksTemplate::getId, Function.identity(), (first, ignored) -> first))
                        .values(),
                viewerId);
        Map<UUID, Long> commentsByPickId = commentRepository.countVisiblePickComments(viewerId, pickIds).stream()
                .collect(Collectors.toMap(CommentRepository.PickCommentCount::getPickId,
                        CommentRepository.PickCommentCount::getCount));
        Set<UUID> likedPickIds = likeService.getLikedPickIds(viewerId, pickIds);
        Map<UUID, PickResponseDTO> responses = new LinkedHashMap<>();

        for (Pick pick : pickList) {
            List<PicksTemplateCategory> categories = categoriesByTemplateId
                    .getOrDefault(pick.getPicksTemplate().getId(), List.of());
            Map<UUID, PicksTemplateCategory> categoryById = categories.stream()
                    .collect(Collectors.toMap(PicksTemplateCategory::getId, Function.identity()));
            List<PickSelectionDTO> selectionDtos = new java.util.ArrayList<>();
            Set<UUID> validCategoryIds = new HashSet<>();

            for (PickSelection selection : selectionsByPickId.getOrDefault(pick.getId(), List.of())) {
                PicksTemplateCategory category = categoryById.get(selection.getCategory().getId());
                boolean valid = category != null
                        && targetService.isValid(viewerId, pick.getPicksTemplate(), category, selection);
                if (valid) {
                    validCategoryIds.add(category.getId());
                }
                selectionDtos.add(new PickSelectionDTO(selection.getId(), selection.getCategory().getId(),
                        pickMapper.pickSelectionToSearchDto(selection), selection.getCreatedAt(),
                        selection.getUpdatedAt(), valid));
            }

            responses.put(pick.getId(), new PickResponseDTO(pick.getId(),
                    templatePreviews.get(pick.getPicksTemplate().getId()), pick.getUser().getId(),
                    pick.getVisibility(), pick.getCreatedAt(), pick.getUpdatedAt(),
                    progress(categories, validCategoryIds), selectionDtos,
                    pick.getLikesCount() == null ? 0 : pick.getLikesCount(),
                    commentsByPickId.getOrDefault(pick.getId(), 0L), likedPickIds.contains(pick.getId())));
        }

        return responses;
    }

    private Map<UUID, List<PicksTemplateCategory>> loadCategoriesByTemplateId(List<Pick> picks) {
        List<UUID> templateIds = picks.stream().map(pick -> pick.getPicksTemplate().getId()).distinct().toList();
        List<PicksTemplateCategory> loaded = categoryRepository.findByPicksTemplateIdInOrderByDisplayOrder(templateIds);
        if (loaded.isEmpty()) {
            loaded = picks.stream().map(Pick::getPicksTemplate).distinct()
                    .flatMap(template -> categoryRepository.findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(
                            template.getId()).stream())
                    .toList();
        }
        return loaded.stream().collect(Collectors.groupingBy(category -> category.getPicksTemplate().getId()));
    }

    private PickProgress progress(List<PicksTemplateCategory> categories, Set<UUID> validCategoryIds) {
        if (validCategoryIds.isEmpty()) {
            return PickProgress.EMPTY;
        }
        boolean complete = categories.stream().map(PicksTemplateCategory::getId).allMatch(validCategoryIds::contains);
        return complete ? PickProgress.COMPLETE : PickProgress.PARTIAL;
    }
}
