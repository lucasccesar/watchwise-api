package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.comment.service.CommentPreviewData;
import com.watchwise.watchwise_api.comment.service.impl.CommentPreviewAssembler;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.pick.dto.PickAnsweredCategoryPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickProgress;
import com.watchwise.watchwise_api.pick.dto.PickViewCardDTO;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pick.mapper.PickMapper;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateOption;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
import com.watchwise.watchwise_api.pickstemplate.service.impl.PicksTemplatePreviewAssembler;
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
    private final CommentPreviewAssembler commentPreviewAssembler;
    private final LikeService likeService;
    private final PickMapper pickMapper;
    private final PickTargetService targetService;
    private final UserMapper userMapper;
    private final PicksTemplatePreviewAssembler templatePreviewAssembler;

    public Page<PickPreviewDTO> assemblePage(Page<Pick> picks, UUID viewerId) {
        if (picks.isEmpty()) {
            return picks.map(pick -> null);
        }
        LoadedData loaded = load(picks.getContent(), viewerId, null);
        Map<UUID, PickPreviewDTO> previews = loaded.picks().stream()
                .collect(Collectors.toMap(Pick::getId, pick -> toPreview(pick, loaded)));
        return picks.map(pick -> previews.get(pick.getId()));
    }

    public Page<PickViewCardDTO> assembleViewPage(Page<Pick> picks, UUID viewerId) {
        if (picks.isEmpty()) {
            return picks.map(pick -> null);
        }
        LoadedData loaded = load(picks.getContent(), viewerId, null);
        Map<UUID, PicksTemplatePreviewDTO> templatePreviews = templatePreviewAssembler.assemble(
                loaded.picks().stream().map(Pick::getPicksTemplate)
                        .collect(Collectors.toMap(PicksTemplate::getId, Function.identity(), (first, ignored) -> first)).values(),
                viewerId);
        Map<UUID, PickViewCardDTO> cards = loaded.picks().stream().collect(Collectors.toMap(Pick::getId,
                pick -> toViewCard(pick, loaded, templatePreviews.get(pick.getPicksTemplate().getId()))));
        return picks.map(pick -> cards.get(pick.getId()));
    }

    public Map<UUID, PickPreviewDTO> assemble(Collection<Pick> picks, UUID viewerId) {
        if (picks.isEmpty()) {
            return Map.of();
        }
        LoadedData loaded = load(picks, viewerId, null);
        return loaded.picks().stream().collect(Collectors.toMap(Pick::getId, pick -> toPreview(pick, loaded)));
    }

    public Map<UUID, PickPreviewDTO> assembleForFeed(Collection<Pick> picks, UUID viewerId) {
        if (picks.isEmpty()) {
            return Map.of();
        }
        List<UUID> pickIds = picks.stream().map(Pick::getId).toList();
        Map<UUID, CommentPreviewData> comments = commentPreviewAssembler.assemblePickPreviews(pickIds, viewerId);
        LoadedData loaded = load(picks, viewerId, comments);
        return loaded.picks().stream().collect(Collectors.toMap(Pick::getId, pick -> toPreview(pick, loaded)));
    }

    private LoadedData load(Collection<Pick> picks, UUID viewerId,
                            Map<UUID, CommentPreviewData> commentPreviews) {
        List<Pick> pickList = List.copyOf(picks);
        List<UUID> pickIds = pickList.stream().map(Pick::getId).toList();
        List<UUID> templateIds = pickList.stream().map(pick -> pick.getPicksTemplate().getId()).distinct().toList();
        Map<UUID, List<PickSelection>> selectionsByPickId = selectionRepository.findByPickIdIn(pickIds).stream()
                .collect(Collectors.groupingBy(selection -> selection.getPick().getId()));
        Map<UUID, List<PicksTemplateCategory>> categoriesByTemplateId = categoryRepository
                .findByPicksTemplateIdInOrderByDisplayOrder(templateIds).stream()
                .collect(Collectors.groupingBy(category -> category.getPicksTemplate().getId()));
        List<UUID> fixedCategoryIds = categoriesByTemplateId.values().stream().flatMap(Collection::stream)
                .filter(category -> category.getOptionMode() == PickCategoryOptionMode.FIXED)
                .map(PicksTemplateCategory::getId).toList();
        Map<UUID, List<PicksTemplateOption>> optionsByCategory = fixedCategoryIds.isEmpty() ? Map.of()
                : optionRepository.findByCategoryIdIn(fixedCategoryIds).stream()
                .collect(Collectors.groupingBy(option -> option.getCategory().getId()));
        Map<UUID, Long> commentsByPickId = commentPreviews != null ? Map.of()
                : commentRepository.countByPickIdIn(pickIds).stream()
                .collect(Collectors.toMap(CommentRepository.PickCommentCount::getPickId,
                        CommentRepository.PickCommentCount::getCount));
        Set<UUID> likedPickIds = likeService.getLikedPickIds(viewerId, pickIds);
        return new LoadedData(pickList, selectionsByPickId, categoriesByTemplateId, optionsByCategory,
                commentsByPickId, likedPickIds, commentPreviews);
    }

    private PickPreviewDTO toPreview(Pick pick, LoadedData loaded) {
        List<PicksTemplateCategory> categories = loaded.categoriesByTemplateId()
                .getOrDefault(pick.getPicksTemplate().getId(), List.of());
        Map<UUID, PickSelection> selectionByCategory = selectionsByCategory(pick, loaded);
        List<PickAnsweredCategoryPreviewDTO> answered = categories.stream()
                .filter(category -> selectionByCategory.containsKey(category.getId()))
                .limit(5)
                .map(category -> answeredCategory(category, selectionByCategory.get(category.getId()), loaded,
                        isValid(category, selectionByCategory.get(category.getId()), loaded)))
                .toList();
        CommentPreviewData commentPreview = loaded.commentPreviews() == null ? null
                : loaded.commentPreviews().get(pick.getId());
        long commentsCount = commentPreview != null ? commentPreview.commentsCount()
                : loaded.commentsByPickId().getOrDefault(pick.getId(), 0L);
        List<CommentResponseDTO> recentComments = commentPreview != null ? commentPreview.recentComments() : List.of();
        return new PickPreviewDTO(pick.getId(), userMapper.userToUserPreviewDto(pick.getUser()), pick.getVisibility(),
                pick.getCreatedAt(), pick.getLikesCount() == null ? 0 : pick.getLikesCount(), commentsCount,
                loaded.likedPickIds().contains(pick.getId()), answered, recentComments);
    }

    private PickViewCardDTO toViewCard(Pick pick, LoadedData loaded, PicksTemplatePreviewDTO templatePreview) {
        List<PicksTemplateCategory> categories = loaded.categoriesByTemplateId()
                .getOrDefault(pick.getPicksTemplate().getId(), List.of());
        Map<UUID, PickSelection> selectionByCategory = selectionsByCategory(pick, loaded);
        List<PickAnsweredCategoryPreviewDTO> validAnswers = categories.stream()
                .filter(category -> selectionByCategory.containsKey(category.getId()))
                .filter(category -> isValid(category, selectionByCategory.get(category.getId()), loaded))
                .map(category -> answeredCategory(category, selectionByCategory.get(category.getId()), loaded, true))
                .toList();
        long validCount = validAnswers.size();
        PickProgress progress = validCount == 0 ? PickProgress.EMPTY
                : validCount == categories.size() ? PickProgress.COMPLETE : PickProgress.PARTIAL;
        return new PickViewCardDTO(pick.getId(), templatePreview, userMapper.userToUserPreviewDto(pick.getUser()),
                pick.getVisibility(), pick.getCreatedAt(), progress, validCount, categories.size(), validAnswers,
                pick.getLikesCount() == null ? 0 : pick.getLikesCount(),
                loaded.commentsByPickId().getOrDefault(pick.getId(), 0L), loaded.likedPickIds().contains(pick.getId()));
    }

    private Map<UUID, PickSelection> selectionsByCategory(Pick pick, LoadedData loaded) {
        return loaded.selectionsByPickId().getOrDefault(pick.getId(), List.of()).stream()
                .collect(Collectors.toMap(selection -> selection.getCategory().getId(), Function.identity()));
    }

    private PickAnsweredCategoryPreviewDTO answeredCategory(PicksTemplateCategory category, PickSelection selection,
                                                             LoadedData loaded, boolean valid) {
        return new PickAnsweredCategoryPreviewDTO(category.getId(), category.getName(), category.getGroup(),
                category.getDisplayOrder(), pickMapper.pickSelectionToSearchDto(selection),
                valid);
    }

    private boolean isValid(PicksTemplateCategory category, PickSelection selection, LoadedData loaded) {
        return targetService.isStructurallyValid(category, selection,
                loaded.optionsByCategory().getOrDefault(category.getId(), List.of()));
    }

    private record LoadedData(List<Pick> picks, Map<UUID, List<PickSelection>> selectionsByPickId,
                              Map<UUID, List<PicksTemplateCategory>> categoriesByTemplateId,
                              Map<UUID, List<PicksTemplateOption>> optionsByCategory,
                              Map<UUID, Long> commentsByPickId, Set<UUID> likedPickIds,
                              Map<UUID, CommentPreviewData> commentPreviews) {
    }
}
