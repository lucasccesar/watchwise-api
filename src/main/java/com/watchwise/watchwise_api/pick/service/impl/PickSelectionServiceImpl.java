package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.pick.dto.PickResponseDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickSelectionService;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pick.service.ResolvedPickTarget;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PickSelectionServiceImpl implements PickSelectionService {
    private final PickRepository pickRepository;
    private final PickSelectionRepository selectionRepository;
    private final PicksTemplateCategoryRepository categoryRepository;
    private final PicksTemplateRepository templateRepository;
    private final PickTargetService targetService;
    private final PickResponseAssembler pickResponseAssembler;

    @Override
    @Transactional
    public PickResponseDTO upsertSelection(UUID userId, UUID pickId, UUID categoryId, PickTargetDTO target) {
        UUID templateId = pickRepository.findOwnedTemplateId(pickId, userId)
                .orElseThrow(() -> new NotFoundException("Pick not found"));
        PicksTemplate template = templateRepository.findByIdForUpdate(templateId)
                .orElseThrow(() -> new NotFoundException("Picks template not found"));
        Pick pick = findOwnedPickForUpdate(userId, pickId);
        PicksTemplateCategory category = categoryRepository.findByIdForUpdate(categoryId)
                .orElseThrow(() -> new NotFoundException("Picks template category not found"));
        if (!category.getPicksTemplate().getId().equals(pick.getPicksTemplate().getId())) {
            throw new BadRequestException("Category does not belong to this Pick's template");
        }

        ResolvedPickTarget resolved = targetService.validateForCategory(userId, template, category, target);
        selectionRepository.findByPickIdAndCategoryIdForUpdate(pickId, categoryId).ifPresent(existing -> {
            selectionRepository.delete(existing);
            selectionRepository.flush();
        });

        LocalDateTime now = LocalDateTime.now();
        selectionRepository.save(PickSelection.builder()
                .pick(pick)
                .category(category)
                .content(resolved.content())
                .personTmdbId(resolved.personTmdbId())
                .contextContent(resolved.contextContent())
                .createdAt(now)
                .updatedAt(now)
                .build());
        pick.setUpdatedAt(now);
        return pickResponseAssembler.assemble(pick, userId);
    }

    @Override
    @Transactional
    public void deleteSelection(UUID userId, UUID pickId, UUID categoryId) {
        Pick pick = findOwnedPickForUpdate(userId, pickId);
        PickSelection selection = selectionRepository.findByPickIdAndCategoryIdForUpdate(pickId, categoryId)
                .orElseThrow(() -> new NotFoundException("Pick selection not found"));
        if (selectionRepository.countByPickId(pickId) <= 1) {
            throw new ConflictException("A Pick must keep at least one selection");
        }
        selectionRepository.delete(selection);
        pick.setUpdatedAt(LocalDateTime.now());
    }

    private Pick findOwnedPickForUpdate(UUID userId, UUID pickId) {
        Pick pick = pickRepository.findByIdForUpdate(pickId).orElseThrow(() -> new NotFoundException("Pick not found"));
        if (!pick.getUser().getId().equals(userId)) {
            throw new NotFoundException("Pick not found");
        }
        return pick;
    }

}
