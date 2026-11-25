package com.watchwise.watchwise_api.pick.service;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.pick.dto.PickListCardDTO;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pick.repository.PickListRow;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateOption;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MyPicksListService {
    private static final int MAX_PAGE_SIZE = 10;
    private static final int MAX_ANSWERED_CATEGORIES = 5;

    private final PickRepository picks;
    private final PickSelectionRepository selections;
    private final PicksTemplateCategoryRepository categories;
    private final PicksTemplateOptionRepository options;
    private final CommentRepository comments;
    private final PickTargetService targets;
    private final PageRequestFactory pages;

    @Transactional(readOnly = true)
    public Page<PickListCardDTO> list(UUID viewerId, Integer page, Integer size) {
        var rows = picks.findMyPickList(viewerId, pages.build(page, size, MAX_PAGE_SIZE));
        if (rows.isEmpty()) {
            return rows.map(row -> null);
        }

        var pickIds = rows.stream().map(PickListRow::id).toList();
        var templateIds = rows.stream().map(PickListRow::templateId).distinct().toList();
        var orderedCategories = categories.findByPicksTemplateIdInOrderByDisplayOrder(templateIds);
        var categoriesByTemplate = orderedCategories.stream()
                .collect(Collectors.groupingBy(category -> category.getPicksTemplate().getId()));
        var selectionsByPick = selections.findByPickIdIn(pickIds).stream().collect(Collectors.groupingBy(
                selection -> selection.getPick().getId(),
                Collectors.toMap(selection -> selection.getCategory().getId(), Function.identity())));
        var fixedIds = orderedCategories.stream()
                .filter(category -> category.getOptionMode() == PickCategoryOptionMode.FIXED)
                .map(PicksTemplateCategory::getId).toList();
        Map<UUID, List<PicksTemplateOption>> optionsByCategory = fixedIds.isEmpty() ? Map.of()
                : options.findByCategoryIdIn(fixedIds).stream()
                        .collect(Collectors.groupingBy(option -> option.getCategory().getId()));
        var commentsByPick = comments.countVisiblePickComments(viewerId, pickIds).stream()
                .collect(Collectors.toMap(CommentRepository.PickCommentCount::getPickId, CommentRepository.PickCommentCount::getCount));

        return rows.map(row -> toCard(row, categoriesByTemplate.getOrDefault(row.templateId(), List.of()),
                selectionsByPick.getOrDefault(row.id(), Map.of()), optionsByCategory,
                commentsByPick.getOrDefault(row.id(), 0L)));
    }

    private PickListCardDTO toCard(PickListRow row, List<PicksTemplateCategory> orderedCategories,
            Map<UUID, PickSelection> selectionsByCategory, Map<UUID, List<PicksTemplateOption>> optionsByCategory,
            long commentsCount) {
        var validCategories = orderedCategories.stream()
                .filter(category -> selectionsByCategory.containsKey(category.getId()))
                .filter(category -> targets.isStructurallyValid(category, selectionsByCategory.get(category.getId()),
                        optionsByCategory.getOrDefault(category.getId(), List.of())))
                .toList();
        var chips = validCategories.stream().limit(MAX_ANSWERED_CATEGORIES)
                .map(category -> new PickListCardDTO.AnsweredCategory(category.getId(), category.getName())).toList();
        var creator = row.creatorUsername() == null ? null
                : new PickListCardDTO.Creator(row.creatorUsername(), row.creatorName(), row.creatorPicture());
        var template = new PickListCardDTO.Template(row.templateName(), row.coverImage(), creator);
        return new PickListCardDTO(row.id(), template, row.visibility(), row.createdAt(), validCategories.size(),
                orderedCategories.size(), chips, row.likesCount() == null ? 0 : row.likesCount(), commentsCount);
    }
}
