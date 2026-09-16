package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.pick.dto.PickCreationDTO;
import com.watchwise.watchwise_api.pick.dto.PickPatchDTO;
import com.watchwise.watchwise_api.pick.dto.PickProgress;
import com.watchwise.watchwise_api.pick.dto.PickResponseDTO;
import com.watchwise.watchwise_api.pick.dto.PickSelectionCreationDTO;
import com.watchwise.watchwise_api.pick.dto.PickSelectionDTO;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.mapper.PickMapper;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickService;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pick.service.ResolvedPickTarget;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.mapper.PicksTemplateMapper;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PickServiceImpl implements PickService {
    private final PickRepository pickRepository;
    private final PickSelectionRepository selectionRepository;
    private final PicksTemplateRepository templateRepository;
    private final PicksTemplateCategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;
    private final PickTargetService targetService;
    private final PickMapper pickMapper;
    private final PicksTemplateMapper templateMapper;
    private final PageRequestFactory pageRequestFactory;

    @Override
    @Transactional
    public PickResponseDTO createPick(UUID userId, UUID templateId, PickCreationDTO dto) {
        validateCreation(dto);
        PicksTemplate template = templateRepository.findByIdForUpdate(templateId)
                .orElseThrow(() -> new NotFoundException("Picks template not found"));
        Map<UUID, PicksTemplateCategory> categories = categoriesById(templateId);

        List<ResolvedSelection> resolvedSelections = new ArrayList<>();
        for (PickSelectionCreationDTO selectionDto : dto.selections()) {
            PicksTemplateCategory category = categories.get(selectionDto.categoryId());
            if (category == null) {
                throw new BadRequestException("Category does not belong to this picks template");
            }
            ResolvedPickTarget target = targetService.validateForCategory(userId, template, category, selectionDto.target());
            resolvedSelections.add(new ResolvedSelection(category, target));
        }

        LocalDateTime now = LocalDateTime.now();
        Pick pick = pickRepository.save(Pick.builder()
                .picksTemplate(template)
                .user(userRepository.getReferenceById(userId))
                .visibility(dto.visibility() == null ? PickVisibility.PUBLIC : dto.visibility())
                .createdAt(now)
                .updatedAt(now)
                .build());

        for (ResolvedSelection resolved : resolvedSelections) {
            selectionRepository.save(buildSelection(pick, resolved.category(), resolved.target(), now));
        }

        return assemble(pick, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PickResponseDTO> getMyPicks(UUID userId, UUID templateId, Integer page, Integer size) {
        if (!templateRepository.existsById(templateId)) {
            throw new NotFoundException("Picks template not found");
        }
        PageRequest pageRequest = pageRequestFactory.build(page, size);
        Page<Pick> picks = pickRepository.findByUserIdAndPicksTemplateId(userId, templateId, pageRequest);
        return mapPage(picks, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public PickResponseDTO getPick(UUID viewerId, UUID pickId) {
        Pick pick = pickRepository.findById(pickId).orElseThrow(() -> new NotFoundException("Pick not found"));
        assertVisible(viewerId, pick);
        return assemble(pick, viewerId);
    }

    @Override
    @Transactional
    public PickResponseDTO updatePick(UUID userId, UUID pickId, PickPatchDTO dto) {
        if (dto == null) {
            throw new BadRequestException("Patch body is required");
        }
        Pick pick = findOwnedPickForUpdate(userId, pickId);
        if (dto.visibility() != null) {
            pick.setVisibility(dto.visibility());
            pick.setUpdatedAt(LocalDateTime.now());
        }
        return assemble(pick, userId);
    }

    @Override
    @Transactional
    public void deletePick(UUID userId, UUID pickId) {
        Pick pick = findOwnedPickForUpdate(userId, pickId);
        pickRepository.delete(pick);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PickResponseDTO> getUserPicks(UUID viewerId, UUID ownerId, UUID templateId, Integer page, Integer size) {
        if (!userRepository.existsById(ownerId)) {
            throw new NotFoundException("User not found");
        }
        PageRequest pageRequest = pageRequestFactory.build(page, size);
        return mapPage(pickRepository.findVisibleByOwner(viewerId, ownerId, templateId, pageRequest), viewerId);
    }

    private Page<PickResponseDTO> mapPage(Page<Pick> picks, UUID viewerId) {
        if (picks.isEmpty()) {
            return picks.map(pick -> null);
        }
        Map<UUID, PickResponseDTO> responses = assemble(picks.getContent(), viewerId);
        return picks.map(pick -> responses.get(pick.getId()));
    }

    private PickResponseDTO assemble(Pick pick, UUID viewerId) {
        return assemble(List.of(pick), viewerId).get(pick.getId());
    }

    private Map<UUID, PickResponseDTO> assemble(List<Pick> picks, UUID viewerId) {
        List<UUID> pickIds = picks.stream().map(Pick::getId).toList();
        Map<UUID, List<PickSelection>> selectionsByPickId = selectionRepository.findByPickIdIn(pickIds).stream()
                .collect(Collectors.groupingBy(selection -> selection.getPick().getId()));
        Map<UUID, List<PicksTemplateCategory>> categoriesByTemplateId = loadCategoriesByTemplateId(picks);
        Map<UUID, PickResponseDTO> responses = new LinkedHashMap<>();

        for (Pick pick : picks) {
            List<PicksTemplateCategory> categories = categoriesByTemplateId.getOrDefault(pick.getPicksTemplate().getId(), List.of());
            Map<UUID, PicksTemplateCategory> categoryById = categories.stream()
                    .collect(Collectors.toMap(PicksTemplateCategory::getId, Function.identity()));
            List<PickSelectionDTO> selectionDtos = new ArrayList<>();
            Set<UUID> validCategoryIds = new HashSet<>();

            for (PickSelection selection : selectionsByPickId.getOrDefault(pick.getId(), List.of())) {
                PicksTemplateCategory category = categoryById.get(selection.getCategory().getId());
                boolean valid = category != null
                        && targetService.isValid(viewerId, pick.getPicksTemplate(), category, selection);
                if (valid) {
                    validCategoryIds.add(category.getId());
                }
                selectionDtos.add(new PickSelectionDTO(selection.getId(), selection.getCategory().getId(),
                        pickMapper.pickSelectionToSearchDto(selection), selection.getCreatedAt(), selection.getUpdatedAt(), valid));
            }

            responses.put(pick.getId(), new PickResponseDTO(pick.getId(),
                    templateMapper.picksTemplateToPreviewDto(pick.getPicksTemplate()), pick.getUser().getId(),
                    pick.getVisibility(), pick.getCreatedAt(), pick.getUpdatedAt(),
                    progress(categories, validCategoryIds), selectionDtos));
        }

        return responses;
    }

    private Map<UUID, List<PicksTemplateCategory>> loadCategoriesByTemplateId(List<Pick> picks) {
        return picks.stream()
                .map(Pick::getPicksTemplate)
                .collect(Collectors.toMap(PicksTemplate::getId, Function.identity(), (first, ignored) -> first))
                .values()
                .stream()
                .collect(Collectors.toMap(PicksTemplate::getId,
                        template -> categoryRepository.findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(template.getId())));
    }

    private PickProgress progress(List<PicksTemplateCategory> categories, Set<UUID> validCategoryIds) {
        if (validCategoryIds.isEmpty()) {
            return PickProgress.EMPTY;
        }
        boolean complete = categories.stream().map(PicksTemplateCategory::getId).allMatch(validCategoryIds::contains);
        return complete ? PickProgress.COMPLETE : PickProgress.PARTIAL;
    }

    private void validateCreation(PickCreationDTO dto) {
        if (dto == null || dto.selections() == null || dto.selections().isEmpty()) {
            throw new BadRequestException("At least one selection is required");
        }
        Set<UUID> categoryIds = new HashSet<>();
        for (PickSelectionCreationDTO selection : dto.selections()) {
            if (selection == null || selection.categoryId() == null) {
                throw new BadRequestException("Selection category is required");
            }
            if (!categoryIds.add(selection.categoryId())) {
                throw new BadRequestException("A category can only be selected once per Pick");
            }
        }
    }

    private Map<UUID, PicksTemplateCategory> categoriesById(UUID templateId) {
        return categoryRepository.findByPicksTemplateIdOrderByGroupAscDisplayOrderAsc(templateId).stream()
                .collect(Collectors.toMap(PicksTemplateCategory::getId, Function.identity()));
    }

    private PickSelection buildSelection(Pick pick, PicksTemplateCategory category, ResolvedPickTarget target, LocalDateTime now) {
        return PickSelection.builder()
                .pick(pick)
                .category(category)
                .content(target.content())
                .personTmdbId(target.personTmdbId())
                .contextContent(target.contextContent())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private Pick findOwnedPickForUpdate(UUID userId, UUID pickId) {
        Pick pick = pickRepository.findByIdForUpdate(pickId).orElseThrow(() -> new NotFoundException("Pick not found"));
        if (!pick.getUser().getId().equals(userId)) {
            throw new NotFoundException("Pick not found");
        }
        return pick;
    }

    private void assertVisible(UUID viewerId, Pick pick) {
        UUID ownerId = pick.getUser().getId();
        if (viewerId.equals(ownerId) || pick.getVisibility() == PickVisibility.PUBLIC) {
            return;
        }
        if (pick.getVisibility() == PickVisibility.FOLLOWERS
                && followerRepository.existsByFollowerIdAndFollowedIdAndStatus(viewerId, ownerId, FollowStatus.ACCEPTED)) {
            return;
        }
        throw new ForbiddenException("This Pick is private");
    }

    private record ResolvedSelection(PicksTemplateCategory category, ResolvedPickTarget target) {
    }
}
