package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.comment.repository.CommentRepository;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.like.service.LikeService;
import com.watchwise.watchwise_api.pick.dto.PickProgress;
import com.watchwise.watchwise_api.pick.dto.PickResponseDTO;
import com.watchwise.watchwise_api.pick.dto.PickSelectionDTO;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pick.mapper.PickMapper;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pick.service.PreparedPickTarget;
import com.watchwise.watchwise_api.pick.service.PreparedTargetValidation;
import com.watchwise.watchwise_api.pick.service.ResolvedPickTarget;
import com.watchwise.watchwise_api.pick.service.StalePickPreparationException;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
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
    private final NewTransactionExecutor transactions;

    public PreparedResponse prepare(UUID pickId) {
        return prepare(pickId, null);
    }

    public PreparedResponse prepare(UUID pickId, UUID replacedCategoryId) {
        List<PreparationSelection> selections = transactions.runInNewTransaction(() ->
                selectionRepository.findByPickIdIn(List.of(pickId)).stream().map(PreparationSelection::capture).toList());
        Map<ResolvedPickTarget.TargetKey, PreparedTargetValidation> validations = new LinkedHashMap<>();
        for (PreparationSelection selection : selections) {
            // Keep its identity below for freshness checks, but omit facts for the discarded target.
            if (selection.categoryId().equals(replacedCategoryId)) continue;
            PreparedTargetValidation prepared = targetService.prepareValidation(selection.asSelection());
            // A skipped category must not discard existence/date facts obtained for another category.
            validations.merge(selection.key(), prepared,
                    (current, next) -> current.checked() && !next.checked() ? current : next);
        }
        return new PreparedResponse(selections.stream().collect(Collectors.toMap(PreparationSelection::id,
                selection -> new SelectionState(selection.categoryId(), selection.key()))), validations);
    }

    public void assertUnchanged(UUID pickId, PreparedResponse prepared) {
        if (!prepared.selections().equals(snapshot(selectionRepository.findByPickIdIn(List.of(pickId))))) {
            throw new StalePickPreparationException();
        }
    }

    public PickResponseDTO assemblePrepared(Pick pick, UUID viewerId, PreparedResponse prepared) {
        return assemble(List.of(pick), viewerId, prepared).get(pick.getId());
    }

    public PickResponseDTO assemble(Pick pick, UUID viewerId) {
        return assemble(List.of(pick), viewerId).get(pick.getId());
    }

    public Map<UUID, PickResponseDTO> assemble(Collection<Pick> picks, UUID viewerId) {
        return assemble(picks, viewerId, null);
    }

    private Map<UUID, PickResponseDTO> assemble(Collection<Pick> picks, UUID viewerId, PreparedResponse prepared) {
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
                PreparedTargetValidation validation = prepared == null ? null
                        : prepared.validations().get(ResolvedPickTarget.keyOf(selection));
                if (prepared != null && validation == null) throw new StalePickPreparationException();
                boolean valid = category != null && (prepared == null
                        ? targetService.isValid(viewerId, pick.getPicksTemplate(), category, selection)
                        : targetService.isValidPrepared(pick.getPicksTemplate(), category, selection, validation));
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

    private Map<UUID, SelectionState> snapshot(List<PickSelection> selections) {
        return selections.stream().collect(Collectors.toMap(PickSelection::getId,
                selection -> new SelectionState(selection.getCategory().getId(), ResolvedPickTarget.keyOf(selection))));
    }

    public record SelectionState(UUID categoryId, ResolvedPickTarget.TargetKey key) {
    }

    private record PreparationSelection(UUID id, UUID categoryId, PickAllowedType allowedType,
            PickCategoryOptionMode optionMode, ResolvedPickTarget.TargetKey key) {
        private static PreparationSelection capture(PickSelection selection) {
            return new PreparationSelection(selection.getId(), selection.getCategory().getId(),
                    selection.getCategory().getAllowedType(), selection.getCategory().getOptionMode(),
                    ResolvedPickTarget.keyOf(selection));
        }

        private PickSelection asSelection() {
            return PickSelection.builder().id(id).category(PicksTemplateCategory.builder().id(categoryId)
                            .allowedType(allowedType).optionMode(optionMode).build())
                    .content(content(key.content())).personTmdbId(key.personTmdbId())
                    .contextContent(content(key.contextContent())).build();
        }

        private Content content(ResolvedPickTarget.ContentKey content) {
            return content == null ? null : Content.builder().type(content.type()).tmdbId(content.tmdbId())
                    .seriesTmdbId(content.seriesTmdbId()).seasonNumber(content.seasonNumber()).episodeNumber(content.episodeNumber()).build();
        }
    }

    public record PreparedResponse(Map<UUID, SelectionState> selections,
            Map<ResolvedPickTarget.TargetKey, PreparedTargetValidation> validations) {
        public PreparedResponse {
            selections = Map.copyOf(selections);
            validations = Map.copyOf(validations);
        }

        public static PreparedResponse forTargets(Collection<PreparedPickTarget> targets) {
            return new PreparedResponse(Map.of(), Map.of()).withTargets(targets);
        }

        public PreparedResponse withTargets(Collection<PreparedPickTarget> targets) {
            Map<ResolvedPickTarget.TargetKey, PreparedTargetValidation> combined = new LinkedHashMap<>(validations);
            for (PreparedPickTarget target : targets) combined.put(target.key(), target.validation());
            return new PreparedResponse(selections, combined);
        }
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
