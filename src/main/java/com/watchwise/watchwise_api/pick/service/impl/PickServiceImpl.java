package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ForbiddenException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.follower.entity.FollowStatus;
import com.watchwise.watchwise_api.follower.repository.FollowerRepository;
import com.watchwise.watchwise_api.pick.dto.PickCreationDTO;
import com.watchwise.watchwise_api.pick.dto.PickPatchDTO;
import com.watchwise.watchwise_api.pick.dto.PickPreviewDTO;
import com.watchwise.watchwise_api.pick.dto.PickResponseDTO;
import com.watchwise.watchwise_api.pick.dto.PickSelectionCreationDTO;
import com.watchwise.watchwise_api.pick.dto.PickSort;
import com.watchwise.watchwise_api.pick.dto.PickDetailViewDTO;
import com.watchwise.watchwise_api.pick.dto.PickTemplatePickViewDTO;
import com.watchwise.watchwise_api.pick.dto.PickViewCardDTO;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickService;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pick.service.ResolvedPickTarget;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class PickServiceImpl implements PickService {
    private static final int MAX_VISUAL_PICK_PAGE_SIZE = 10;

    private final PickRepository pickRepository;
    private final PickSelectionRepository selectionRepository;
    private final PicksTemplateRepository templateRepository;
    private final PicksTemplateCategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final FollowerRepository followerRepository;
    private final PickTargetService targetService;
    private final PageRequestFactory pageRequestFactory;
    private final PickResponseAssembler pickResponseAssembler;
    private final PickPreviewAssembler pickPreviewAssembler;
    private final UserBlockAccessService userBlockAccessService;

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
    public Page<PickPreviewDTO> getMyPicks(UUID userId, UUID templateId, Integer page, Integer size) {
        assertTemplateVisible(userId, templateId);
        PageRequest pageRequest = pageRequestFactory.build(page, size);
        Page<Pick> picks = pickRepository.findByUserIdAndPicksTemplateIdOrderByCreatedAtDescIdDesc(userId, templateId, pageRequest);
        return mapPreviewPage(picks, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PickViewCardDTO> getMyPicksView(UUID viewerId, Integer page, Integer size) {
        PageRequest pageRequest = pageRequestFactory.build(page, size, MAX_VISUAL_PICK_PAGE_SIZE);
        Page<Pick> picks = pickRepository.findVisibleByUserId(viewerId, viewerId, pageRequest);
        return pickPreviewAssembler.assembleViewPage(picks, viewerId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PickTemplatePickViewDTO> getTemplatePicksView(
            UUID viewerId, UUID templateId, PickSort sort, Integer page, Integer size) {
        assertTemplateVisible(viewerId, templateId);
        PageRequest pageRequest = pageRequestFactory.build(page, size, MAX_VISUAL_PICK_PAGE_SIZE);
        Page<Pick> picks = sort == PickSort.POPULAR
                ? pickRepository.findVisibleByTemplatePopular(viewerId, templateId, pageRequest)
                : pickRepository.findVisibleByTemplateRecent(viewerId, templateId, pageRequest);
        return pickPreviewAssembler.assembleTemplateViewPage(picks, viewerId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PickTemplatePickViewDTO> getMyPicksView(
            UUID viewerId, UUID templateId, Integer page, Integer size) {
        assertTemplateVisible(viewerId, templateId);
        PageRequest pageRequest = pageRequestFactory.build(page, size, MAX_VISUAL_PICK_PAGE_SIZE);
        Page<Pick> picks = pickRepository.findByUserIdAndPicksTemplateIdOrderByCreatedAtDescIdDesc(
                viewerId, templateId, pageRequest);
        return pickPreviewAssembler.assembleTemplateViewPage(picks, viewerId);
    }

    @Override
    @Transactional(readOnly = true)
    public PickDetailViewDTO getPickView(UUID viewerId, UUID pickId) {
        Pick pick = pickRepository.findByIdWithUserAndTemplateAndCreator(pickId)
                .orElseThrow(() -> new NotFoundException("Pick not found"));
        assertVisible(viewerId, pick);
        return pickPreviewAssembler.assembleDetail(pick, viewerId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PickPreviewDTO> getTemplatePicks(UUID viewerId, UUID templateId, PickSort sort, Integer page, Integer size) {
        assertTemplateVisible(viewerId, templateId);
        PageRequest pageRequest = pageRequestFactory.build(page, size);
        Page<Pick> picks = sort == PickSort.POPULAR
                ? pickRepository.findVisibleByTemplatePopular(viewerId, templateId, pageRequest)
                : pickRepository.findVisibleByTemplateRecent(viewerId, templateId, pageRequest);
        return mapPreviewPage(picks, viewerId);
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
    public Page<PickPreviewDTO> getUserPicks(UUID viewerId, UUID ownerId, UUID templateId, Integer page, Integer size) {
        if (!userRepository.existsById(ownerId)) {
            throw new NotFoundException("User not found");
        }
        userBlockAccessService.assertContentVisible(viewerId, ownerId);
        PageRequest pageRequest = pageRequestFactory.build(page, size);
        return mapPreviewPage(pickRepository.findVisibleByOwner(viewerId, ownerId, templateId, pageRequest), viewerId);
    }

    private Page<PickResponseDTO> mapPage(Page<Pick> picks, UUID viewerId) {
        if (picks.isEmpty()) {
            return picks.map(pick -> null);
        }
        Map<UUID, PickResponseDTO> responses = pickResponseAssembler.assemble(picks.getContent(), viewerId);
        return picks.map(pick -> responses.get(pick.getId()));
    }

    private Page<PickPreviewDTO> mapPreviewPage(Page<Pick> picks, UUID viewerId) {
        return pickPreviewAssembler.assemblePage(picks, viewerId);
    }

    private void assertTemplateVisible(UUID viewerId, UUID templateId) {
        PicksTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("Picks template not found"));
        if (template.getCreator() != null) {
            userBlockAccessService.assertContentVisible(viewerId, template.getCreator().getId());
        }
    }

    private PickResponseDTO assemble(Pick pick, UUID viewerId) {
        return pickResponseAssembler.assemble(pick, viewerId);
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
        userBlockAccessService.assertContentVisible(viewerId, ownerId);
        if (pick.getPicksTemplate().getCreator() != null) {
            userBlockAccessService.assertContentVisible(viewerId, pick.getPicksTemplate().getCreator().getId());
        }
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
