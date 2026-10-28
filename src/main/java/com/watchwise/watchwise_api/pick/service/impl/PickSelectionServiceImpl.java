package com.watchwise.watchwise_api.pick.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.transaction.NewTransactionExecutor;
import com.watchwise.watchwise_api.pick.dto.PickResponseDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pick.entity.Pick;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import com.watchwise.watchwise_api.pick.repository.PickRepository;
import com.watchwise.watchwise_api.pick.repository.PickSelectionRepository;
import com.watchwise.watchwise_api.pick.service.PickSelectionService;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pick.service.ResolvedPickTarget;
import com.watchwise.watchwise_api.pick.service.PreparedPickTarget;
import com.watchwise.watchwise_api.pick.service.PickPreparationRules;
import com.watchwise.watchwise_api.pick.service.StalePickPreparationException;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.userblock.service.UserBlockAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

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
    private final UserBlockAccessService userBlockAccessService;
    private final NewTransactionExecutor transactions;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PickResponseDTO upsertSelection(UUID userId, UUID pickId, UUID categoryId, PickTargetDTO target) {
        UUID templateId = pickRepository.findOwnedTemplateId(pickId, userId)
                .orElseThrow(() -> new NotFoundException("Pick not found"));
        transactions.runInNewTransaction(() -> {
            lockContentAccess(userId, templateId);
            return null;
        });
        for (int attempt = 0; attempt < 4; attempt++) {
            PickPreparationRules preparation = transactions.runInNewTransaction(() -> PickPreparationRules.capture(
                    templateRepository.findById(templateId).orElseThrow(() -> new NotFoundException("Picks template not found")),
                    List.of(categoryRepository.findById(categoryId).orElseThrow(() -> new NotFoundException("Picks template category not found")))));
            PicksTemplate preparationTemplate = preparation.template();
            PicksTemplateCategory preparationCategory = preparation.category(categoryId);
            if (!preparationCategory.getPicksTemplate().getId().equals(templateId)) {
                throw new BadRequestException("Category does not belong to this Pick's template");
            }
            PreparedPickTarget preparedTarget = targetService.prepareForCategory(userId, preparationTemplate,
                    preparationCategory, target);
            PickResponseAssembler.PreparedResponse preparedResponse = pickResponseAssembler.prepare(pickId, categoryId);
            try {
                return transactions.runInNewTransaction(() -> {
                    lockContentAccess(userId, templateId);
                    PicksTemplate template = templateRepository.findByIdForUpdate(templateId)
                            .orElseThrow(() -> new NotFoundException("Picks template not found"));
                    Pick pick = findOwnedPickForUpdate(userId, pickId);
                    if (!pick.getPicksTemplate().getId().equals(templateId)) throw new NotFoundException("Pick not found");
                    PicksTemplateCategory category = categoryRepository.findByIdForUpdate(categoryId)
                            .orElseThrow(() -> new NotFoundException("Picks template category not found"));
                    if (!category.getPicksTemplate().getId().equals(templateId)) {
                        throw new BadRequestException("Category does not belong to this Pick's template");
                    }
                    pickResponseAssembler.assertUnchanged(pickId, preparedResponse);
                    ResolvedPickTarget resolved = targetService.resolvePreparedForCategory(template, category, preparedTarget);
                    selectionRepository.findByPickIdAndCategoryIdForUpdate(pickId, categoryId).ifPresent(existing -> {
                        selectionRepository.delete(existing);
                        selectionRepository.flush();
                    });
                    LocalDateTime now = LocalDateTime.now();
                    selectionRepository.save(PickSelection.builder().pick(pick).category(category)
                            .content(resolved.content()).personTmdbId(resolved.personTmdbId())
                            .contextContent(resolved.contextContent()).createdAt(now).updatedAt(now).build());
                    pick.setUpdatedAt(now);
                    return pickResponseAssembler.assemblePrepared(pick, userId,
                            preparedResponse.withTargets(List.of(preparedTarget)));
                });
            } catch (StalePickPreparationException stale) {
                if (attempt == 3) throw new ConflictException("Pick selections or category rules changed while preparing the response; retry the request");
            }
        }
        throw new IllegalStateException("Pick preparation attempts exhausted");
    }

    private void lockContentAccess(UUID userId, UUID templateId) {
        PicksTemplateRepository.CreatorIdentity identity = templateRepository.findCreatorIdentityById(templateId)
                .orElseThrow(() -> new NotFoundException("Picks template not found"));
        if (identity.getCreatorId() != null) {
            userBlockAccessService.lockAndAssertContentVisible(userId, identity.getCreatorId());
        }
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
