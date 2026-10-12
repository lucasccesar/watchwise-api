package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.comment.service.CommentPreviewData;
import com.watchwise.watchwise_api.comment.service.impl.CommentPreviewAssembler;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.pick.dto.PickAnsweredCategoryPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickAnswerViewDTO;
import com.watchwise.watchwise_api.pick.dto.PickPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickProgress;
import com.watchwise.watchwise_api.pick.dto.PickDetailViewDTO;
import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickTemplatePickViewDTO;
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
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
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
    private final PickTargetPreviewAssembler targetPreviewAssembler;
    private final UserRepository userRepository;

    public PickPreviewAssembler(PickSelectionRepository selectionRepository,
            PicksTemplateCategoryRepository categoryRepository, PicksTemplateOptionRepository optionRepository,
            CommentRepository commentRepository, CommentPreviewAssembler commentPreviewAssembler,
            LikeService likeService, PickMapper pickMapper, PickTargetService targetService,
            UserMapper userMapper, PicksTemplatePreviewAssembler templatePreviewAssembler) {
        this(selectionRepository, categoryRepository, optionRepository, commentRepository, commentPreviewAssembler,
                likeService, pickMapper, targetService, userMapper, templatePreviewAssembler, null, null);
    }

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
        VisualData visual = assembleVisualData(loaded, viewerId);
        Map<UUID, PicksTemplatePreviewDTO> templatePreviews = assembleTemplatePreviews(loaded, viewerId);
        Map<UUID, PickViewCardDTO> cards = loaded.picks().stream().collect(Collectors.toMap(Pick::getId,
                pick -> toViewCard(pick, loaded, templatePreviews.get(pick.getPicksTemplate().getId()), visual)));
        return picks.map(pick -> cards.get(pick.getId()));
    }

    public Page<PickTemplatePickViewDTO> assembleTemplateViewPage(Page<Pick> picks, UUID viewerId) {
        if (picks.isEmpty()) {
            return picks.map(pick -> null);
        }
        LoadedData loaded = load(picks.getContent(), viewerId, null);
        VisualData visual = assembleVisualData(loaded, viewerId);
        Map<UUID, PickTemplatePickViewDTO> views = loaded.picks().stream().collect(Collectors.toMap(Pick::getId,
                pick -> toTemplatePickView(pick, loaded, visual)));
        return picks.map(pick -> views.get(pick.getId()));
    }

    public PickDetailViewDTO assembleDetail(Pick pick, UUID viewerId) {
        Objects.requireNonNull(pick, "pick is required");
        LoadedData loaded = load(List.of(pick), viewerId, null);
        VisualData visual = assembleVisualData(loaded, viewerId);
        PicksTemplatePreviewDTO templatePreview = assembleTemplatePreviews(loaded, viewerId)
                .get(pick.getPicksTemplate().getId());
        List<PickAnswerViewDTO> answers = visualAnswers(pick, loaded, visual, true);
        long answeredCount = answers.stream().filter(PickAnswerViewDTO::isValid).count();
        return new PickDetailViewDTO(pick.getId(), templatePreview, userMapper.userToUserPreviewDto(pick.getUser()),
                pick.getVisibility(), pick.getCreatedAt(), pick.getUpdatedAt(), progress(answers), answeredCount,
                answers.size(), answers, pick.getLikesCount() == null ? 0 : pick.getLikesCount(),
                loaded.commentsByPickId().getOrDefault(pick.getId(), 0L), loaded.likedPickIds().contains(pick.getId()));
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

    private PickViewCardDTO toViewCard(Pick pick, LoadedData loaded, PicksTemplatePreviewDTO templatePreview,
            VisualData visual) {
        List<PicksTemplateCategory> categories = loaded.categoriesByTemplateId()
                .getOrDefault(pick.getPicksTemplate().getId(), List.of());
        List<PickAnswerViewDTO> validAnswers = visualAnswers(pick, loaded, visual, false).stream()
                .filter(answer -> answer.isAnswered() && answer.isValid())
                .toList();
        long validCount = validAnswers.size();
        PickProgress progress = validCount == 0 ? PickProgress.EMPTY
                : validCount == categories.size() ? PickProgress.COMPLETE : PickProgress.PARTIAL;
        return new PickViewCardDTO(pick.getId(), templatePreview, userMapper.userToUserPreviewDto(pick.getUser()),
                pick.getVisibility(), pick.getCreatedAt(), progress, validCount, categories.size(), validAnswers,
                pick.getLikesCount() == null ? 0 : pick.getLikesCount(),
                loaded.commentsByPickId().getOrDefault(pick.getId(), 0L), loaded.likedPickIds().contains(pick.getId()));
    }

    private PickTemplatePickViewDTO toTemplatePickView(Pick pick, LoadedData loaded, VisualData visual) {
        List<PickAnswerViewDTO> allValidAnswers = visualAnswers(pick, loaded, visual, false).stream()
                .filter(answer -> answer.isAnswered() && answer.isValid())
                .toList();
        long validCount = allValidAnswers.size();
        List<PickAnswerViewDTO> validAnswers = allValidAnswers.stream().limit(5).toList();
        List<PicksTemplateCategory> categories = loaded.categoriesByTemplateId()
                .getOrDefault(pick.getPicksTemplate().getId(), List.of());
        return new PickTemplatePickViewDTO(pick.getId(), userMapper.userToUserPreviewDto(pick.getUser()),
                pick.getVisibility(), pick.getCreatedAt(), progress(validCount, categories.size()), validCount,
                categories.size(), validAnswers, pick.getLikesCount() == null ? 0 : pick.getLikesCount(),
                loaded.commentsByPickId().getOrDefault(pick.getId(), 0L), loaded.likedPickIds().contains(pick.getId()));
    }

    private List<PickAnswerViewDTO> visualAnswers(Pick pick, LoadedData loaded, VisualData visual,
            boolean includeUnanswered) {
        List<PicksTemplateCategory> categories = loaded.categoriesByTemplateId()
                .getOrDefault(pick.getPicksTemplate().getId(), List.of());
        Map<UUID, PickSelection> selectionByCategory = selectionsByCategory(pick, loaded);
        return categories.stream()
                .filter(category -> includeUnanswered || selectionByCategory.containsKey(category.getId()))
                .map(category -> visualAnswer(category, selectionByCategory.get(category.getId()), visual, loaded))
                .toList();
    }

    private PickAnswerViewDTO visualAnswer(PicksTemplateCategory category, PickSelection selection,
            VisualData visual, LoadedData loaded) {
        boolean answered = selection != null;
        boolean valid = answered && isValid(category, selection, loaded);
        PickOptionSearchDTO target = answered ? visual.targetsBySelectionId().get(selection.getId()) : null;
        PickTargetPreviewDTO preview = answered ? visual.previewsBySelectionId().get(selection.getId()) : null;
        return new PickAnswerViewDTO(category.getId(), category.getName(), category.getGroup(), category.getDisplayOrder(),
                category.getAllowedType(), category.getOptionMode(), target, preview, answered, valid);
    }

    private PickProgress progress(List<PickAnswerViewDTO> answers) {
        long validCount = answers.stream().filter(PickAnswerViewDTO::isValid).count();
        return progress(validCount, answers.size());
    }

    private PickProgress progress(long validCount, long totalCount) {
        if (validCount == 0) {
            return PickProgress.EMPTY;
        }
        return validCount == totalCount ? PickProgress.COMPLETE : PickProgress.PARTIAL;
    }

    private Map<UUID, PicksTemplatePreviewDTO> assembleTemplatePreviews(LoadedData loaded, UUID viewerId) {
        return templatePreviewAssembler == null ? Map.of() : templatePreviewAssembler.assemble(
                loaded.picks().stream().map(Pick::getPicksTemplate)
                        .collect(Collectors.toMap(PicksTemplate::getId, Function.identity(), (first, ignored) -> first)).values(),
                viewerId);
    }

    private VisualData assembleVisualData(LoadedData loaded, UUID viewerId) {
        if (targetPreviewAssembler == null) {
            return new VisualData(Map.of(), Map.of());
        }
        Map<UUID, PickOptionSearchDTO> targetsBySelectionId = new LinkedHashMap<>();
        for (List<PickSelection> selections : loaded.selectionsByPickId().values()) {
            for (PickSelection selection : selections) {
                if (selection.getId() != null) {
                    targetsBySelectionId.put(selection.getId(), pickMapper.pickSelectionToSearchDto(selection));
                }
            }
        }
        List<UUID> selectionIds = new java.util.ArrayList<>();
        List<PickOptionSearchDTO> targets = new java.util.ArrayList<>();
        targetsBySelectionId.forEach((selectionId, target) -> {
            if (hasTarget(target)) {
                selectionIds.add(selectionId);
                targets.add(target);
            }
        });
        if (targets.isEmpty()) {
            return new VisualData(targetsBySelectionId, Map.of());
        }
        List<PickTargetPreviewDTO> previews = targetPreviewAssembler.assemble(targets, cardContext(viewerId));
        Map<UUID, PickTargetPreviewDTO> previewsBySelectionId = new LinkedHashMap<>();
        if (previews != null) {
            for (int index = 0; index < Math.min(selectionIds.size(), previews.size()); index++) {
                previewsBySelectionId.put(selectionIds.get(index), previews.get(index));
            }
        }
        return new VisualData(targetsBySelectionId, previewsBySelectionId);
    }

    private boolean hasTarget(PickOptionSearchDTO target) {
        return target != null && (target.content() != null || target.personTmdbId() != null);
    }

    private ContentCardContext cardContext(UUID viewerId) {
        User viewer = null;
        if (viewerId != null && userRepository != null) {
            Optional<User> result = userRepository.findById(viewerId);
            viewer = result == null ? null : result.orElse(null);
        }
        String language = viewer == null || viewer.getPreferredLanguage() == null
                ? TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE : viewer.getPreferredLanguage();
        String region = viewer == null || viewer.getPreferredRegion() == null ? "US" : viewer.getPreferredRegion();
        return new ContentCardContext(language, region, null, null);
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

    private record VisualData(Map<UUID, PickOptionSearchDTO> targetsBySelectionId,
                              Map<UUID, PickTargetPreviewDTO> previewsBySelectionId) {
    }
}
