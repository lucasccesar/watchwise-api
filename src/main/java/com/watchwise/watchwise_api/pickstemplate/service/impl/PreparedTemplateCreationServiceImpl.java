package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pick.service.ResolvedPickTarget;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateOptionCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateCreation;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateCreationService;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateOption;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateDuplicateTargetException;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateTargetNotFoundException;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateTargetValidationException;
import com.watchwise.watchwise_api.pickstemplate.service.TemplateOptionPosition;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PreparedTemplateCreationServiceImpl implements PreparedTemplateCreationService {
    private final UserRepository users;
    private final PickTargetService targets;
    private final PreparedTemplateWriter writer;
    private final PicksTemplateCreationValidator requestValidator;
    private final Clock clock;

    public PreparedTemplateCreationServiceImpl(UserRepository users, PickTargetService targets,
                                               PreparedTemplateWriter writer,
                                               PicksTemplateCreationValidator requestValidator, Clock clock) {
        this.users = users;
        this.targets = targets;
        this.writer = writer;
        this.requestValidator = requestValidator;
        this.clock = clock;
    }

    @Override
    public PreparedTemplateCreation prepare(UUID actorId, PicksTemplateCreationDTO dto) {
        if (actorId == null) throw new BadRequestException("Template creator is required");
        users.findById(actorId).orElseThrow(() -> new NotFoundException("User not found"));
        requestValidator.validate(dto);
        Set<PreparationTargetIdentity> distinctTargetIdentities = new HashSet<>();
        for (PicksTemplateCategoryCreationDTO category : dto.categories()) {
            if (category.options() == null) continue;
            for (PicksTemplateOptionCreationDTO option : category.options()) {
                if (option != null && option.target() != null) {
                    distinctTargetIdentities.add(new PreparationTargetIdentity(category.allowedType(), option.target()));
                }
            }
        }
        if (distinctTargetIdentities.size() > 100) {
            throw new BadRequestException("A template preparation can contain at most 100 distinct target identities");
        }

        PicksTemplate templateContext = PicksTemplate.builder()
                .eligibilityStartDate(dto.eligibilityStartDate())
                .eligibilityEndDate(dto.eligibilityEndDate())
                .build();
        Map<ValidationKey, ResolvedPickTarget> validatedTargets = new HashMap<>();
        List<PreparedTemplateOption> preparedOptions = new ArrayList<>();

        for (int categoryIndex = 0; categoryIndex < dto.categories().size(); categoryIndex++) {
            PicksTemplateCategoryCreationDTO categoryDto = dto.categories().get(categoryIndex);
            PicksTemplateCategory categoryContext = PicksTemplateCategory.builder()
                    .picksTemplate(templateContext)
                    .allowedType(categoryDto.allowedType())
                    .optionMode(categoryDto.optionMode())
                    .build();
            List<PicksTemplateOptionCreationDTO> categoryOptions = categoryDto.options();
            if (categoryOptions == null) continue;

            Set<ResolvedPickTarget.TargetKey> uniqueTargets = new HashSet<>();
            for (int optionIndex = 0; optionIndex < categoryOptions.size(); optionIndex++) {
                PicksTemplateOptionCreationDTO option = categoryOptions.get(optionIndex);
                PickTargetDTO target = option == null ? null : option.target();
                if (target == null) throw new BadRequestException("Fixed option target is required");

                ValidationKey key = new ValidationKey(categoryDto.allowedType(), target,
                        dto.eligibilityStartDate(), dto.eligibilityEndDate());
                ResolvedPickTarget resolved = validatedTargets.get(key);
                if (resolved == null) {
                    try {
                        resolved = targets.validateFixedOption(actorId, templateContext, categoryContext, target);
                    } catch (NotFoundException exception) {
                        throw new PreparedTemplateTargetNotFoundException(
                                new TemplateOptionPosition(categoryIndex, optionIndex), exception.getMessage());
                    } catch (BadRequestException exception) {
                        throw new PreparedTemplateTargetValidationException(
                                new TemplateOptionPosition(categoryIndex, optionIndex));
                    }
                    if (resolved == null) {
                        throw new PreparedTemplateTargetNotFoundException(
                                new TemplateOptionPosition(categoryIndex, optionIndex),
                                "The fixed nominee was not found in TMDB");
                    }
                    validatedTargets.put(key, resolved);
                }
                if (resolved.key() == null || !uniqueTargets.add(resolved.key())) {
                    throw new PreparedTemplateDuplicateTargetException(
                            new TemplateOptionPosition(categoryIndex, optionIndex));
                }
                preparedOptions.add(new PreparedTemplateOption(categoryIndex, optionIndex,
                        resolved.content() == null ? null : resolved.content().getId(),
                        resolved.personTmdbId(),
                        resolved.contextContent() == null ? null : resolved.contextContent().getId()));
            }
        }

        PicksTemplateCreationDTO snapshot = snapshot(dto);
        List<PreparedTemplateOption> immutableOptions = List.copyOf(preparedOptions);
        LocalDateTime validatedAt = LocalDateTime.now(clock);
        String digest = PreparedTemplateCreationDigest.compute(actorId, snapshot, immutableOptions, validatedAt);
        return new PreparedTemplateCreation(actorId, snapshot, immutableOptions, digest, validatedAt);
    }

    @Override
    public com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateResponseDTO createPrepared(
            UUID actorId, PreparedTemplateCreation prepared) {
        if (prepared == null || actorId == null || !actorId.equals(prepared.actorId())) {
            throw new BadRequestException("Prepared template does not belong to this creator");
        }
        return writer.createPrepared(actorId, prepared);
    }

    private PicksTemplateCreationDTO snapshot(PicksTemplateCreationDTO dto) {
        List<PicksTemplateCategoryCreationDTO> categories = dto.categories().stream()
                .map(category -> new PicksTemplateCategoryCreationDTO(category.name(), category.description(),
                        category.group(), category.displayOrder(), category.allowedType(), category.optionMode(),
                        category.options() == null ? null : List.copyOf(category.options())))
                .toList();
        return new PicksTemplateCreationDTO(dto.name(), dto.description(), dto.coverImage(), dto.instructions(),
                dto.eligibilityStartDate(), dto.eligibilityEndDate(), categories);
    }

    private record ValidationKey(PickAllowedType type, PickTargetDTO target,
                                 java.time.LocalDate start, java.time.LocalDate end) { }

    private record PreparationTargetIdentity(PickAllowedType type, PickTargetDTO target) { }
}
