package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.ConflictException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.pick.dto.PickContentTargetDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateOptionCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateResponseDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateOption;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateCategoryRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateOptionRepository;
import com.watchwise.watchwise_api.pickstemplate.repository.PicksTemplateRepository;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateCreation;
import com.watchwise.watchwise_api.pickstemplate.service.PreparedTemplateOption;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.entity.UserRole;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class PreparedTemplateWriter {
    private final UserRepository users;
    private final ContentRepository contents;
    private final PicksTemplateRepository templates;
    private final PicksTemplateCategoryRepository categories;
    private final PicksTemplateOptionRepository options;
    private final PicksTemplateResponseAssembler responses;
    private final PicksTemplateCreationValidator requestValidator;
    private final Clock clock;

    public PreparedTemplateWriter(UserRepository users, ContentRepository contents,
                                  PicksTemplateRepository templates,
                                  PicksTemplateCategoryRepository categories,
                                  PicksTemplateOptionRepository options,
                                  PicksTemplateResponseAssembler responses,
                                  PicksTemplateCreationValidator requestValidator, Clock clock) {
        this.users = users;
        this.contents = contents;
        this.templates = templates;
        this.categories = categories;
        this.options = options;
        this.responses = responses;
        this.requestValidator = requestValidator;
        this.clock = clock;
    }

    @Transactional
    public PicksTemplateResponseDTO createPrepared(UUID actorId, PreparedTemplateCreation prepared) {
        if (actorId == null || prepared == null || !actorId.equals(prepared.actorId())) {
            throw new BadRequestException("Prepared template does not belong to this creator");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!prepared.isFreshAt(now)) {
            throw new ConflictException("Prepared template validation expired");
        }
        if (!PreparedTemplateCreationDigest.compute(prepared.actorId(), prepared.sourceDto(), prepared.options(),
                prepared.validatedAt())
                .equals(prepared.validationDigest())) {
            throw new BadRequestException("Prepared template validation is stale");
        }
        PicksTemplateCreationDTO dto = prepared.sourceDto();
        requestValidator.validate(dto);
        User actor = users.findById(actorId).orElseThrow(() -> new NotFoundException("User not found"));

        Map<Index, PreparedTemplateOption> preparedByIndex = indexOptions(prepared, dto);
        Map<UUID, Content> contentById = loadContent(prepared.options());
        validateTargets(dto, preparedByIndex, contentById);

        PicksTemplate template = PicksTemplate.builder().creator(actor)
                .origin(actor.getRole() == UserRole.ADMIN ? PickOrigin.OFFICIAL : PickOrigin.COMMUNITY)
                .name(dto.name()).description(dto.description()).coverImage(dto.coverImage()).instructions(dto.instructions())
                .eligibilityStartDate(dto.eligibilityStartDate()).eligibilityEndDate(dto.eligibilityEndDate())
                .createdAt(now).updatedAt(now).build();
        PicksTemplate savedTemplate = templates.saveAndFlush(template);

        for (int categoryIndex = 0; categoryIndex < dto.categories().size(); categoryIndex++) {
            PicksTemplateCategoryCreationDTO categoryDto = dto.categories().get(categoryIndex);
            PicksTemplateCategory category = PicksTemplateCategory.builder().picksTemplate(savedTemplate)
                    .name(categoryDto.name()).description(categoryDto.description()).group(categoryDto.group())
                    .displayOrder(categoryDto.displayOrder()).allowedType(categoryDto.allowedType())
                    .optionMode(categoryDto.optionMode()).createdAt(now).updatedAt(now).build();
            PicksTemplateCategory savedCategory = categories.saveAndFlush(category);

            List<PicksTemplateOptionCreationDTO> categoryOptions = categoryDto.options();
            if (categoryOptions == null) continue;
            Set<TargetIdentity> seen = new HashSet<>();
            for (int optionIndex = 0; optionIndex < categoryOptions.size(); optionIndex++) {
                PreparedTemplateOption preparedOption = preparedByIndex.get(new Index(categoryIndex, optionIndex));
                if (preparedOption == null) throw new BadRequestException("Prepared fixed option is missing");
                if (!seen.add(new TargetIdentity(preparedOption.contentId(), preparedOption.personTmdbId(),
                        preparedOption.contextContentId()))) {
                    throw new ConflictException("This target is already a fixed option for this category");
                }
                PicksTemplateOption option = PicksTemplateOption.builder()
                        .category(savedCategory)
                        .content(reference(contentById, preparedOption.contentId()))
                        .personTmdbId(preparedOption.personTmdbId())
                        .contextContent(reference(contentById, preparedOption.contextContentId()))
                        .createdAt(now)
                        .build();
                FixedOptionPersistence.saveAndFlush(options, option);
            }
        }
        return responses.toResponse(savedTemplate, actorId);
    }

    private Map<Index, PreparedTemplateOption> indexOptions(PreparedTemplateCreation prepared,
                                                            PicksTemplateCreationDTO dto) {
        Map<Index, PreparedTemplateOption> result = new HashMap<>();
        for (PreparedTemplateOption option : prepared.options()) {
            if (option.categoryIndex() >= dto.categories().size()) {
                throw new BadRequestException("Prepared option category is invalid");
            }
            List<PicksTemplateOptionCreationDTO> categoryOptions = dto.categories()
                    .get(option.categoryIndex()).options();
            if (categoryOptions == null || option.optionIndex() >= categoryOptions.size()
                    || result.putIfAbsent(new Index(option.categoryIndex(), option.optionIndex()), option) != null) {
                throw new BadRequestException("Prepared option index is invalid");
            }
        }
        int expected = dto.categories().stream().map(PicksTemplateCategoryCreationDTO::options)
                .filter(Objects::nonNull).mapToInt(List::size).sum();
        if (result.size() != expected) throw new BadRequestException("Prepared options do not match the template");
        return result;
    }

    private Map<UUID, Content> loadContent(List<PreparedTemplateOption> preparedOptions) {
        Set<UUID> ids = new HashSet<>();
        for (PreparedTemplateOption option : preparedOptions) {
            if (option.contentId() != null) ids.add(option.contentId());
            if (option.contextContentId() != null) ids.add(option.contextContentId());
        }
        if (ids.isEmpty()) return Map.of();
        Map<UUID, Content> result = new HashMap<>();
        for (Content content : contents.findAllById(ids)) result.put(content.getId(), content);
        if (result.size() != ids.size()) throw new NotFoundException("Prepared content reference not found");
        return result;
    }

    private void validateTargets(PicksTemplateCreationDTO dto,
                                 Map<Index, PreparedTemplateOption> preparedByIndex,
                                 Map<UUID, Content> contentById) {
        for (int categoryIndex = 0; categoryIndex < dto.categories().size(); categoryIndex++) {
            PicksTemplateCategoryCreationDTO category = dto.categories().get(categoryIndex);
            if (category.options() == null) continue;
            for (int optionIndex = 0; optionIndex < category.options().size(); optionIndex++) {
                PreparedTemplateOption prepared = preparedByIndex.get(new Index(categoryIndex, optionIndex));
                PickTargetDTO source = category.options().get(optionIndex).target();
                if (category.allowedType() == PickAllowedType.PERSON) {
                    if (prepared.contentId() != null || prepared.personTmdbId() == null
                            || !prepared.personTmdbId().matches("[0-9]+")
                            || !prepared.personTmdbId().equals(source.personTmdbId())) {
                        throw new BadRequestException("Prepared person option does not match its source");
                    }
                    validateContext(source.contextContent(), prepared.contextContentId(), contentById);
                } else {
                    if (prepared.personTmdbId() != null || prepared.contextContentId() != null
                            || prepared.contentId() == null) {
                        throw new BadRequestException("Prepared content option does not match its source");
                    }
                    Content content = contentById.get(prepared.contentId());
                    if (content == null || content.getType() != contentType(category.allowedType())
                            || !sameReference(content, source.content())) {
                        throw new NotFoundException("Prepared content reference not found");
                    }
                }
            }
        }
    }

    private void validateContext(PickContentTargetDTO source, UUID contextId, Map<UUID, Content> contentById) {
        if (source == null) {
            if (contextId != null) throw new BadRequestException("Prepared person context does not match its source");
            return;
        }
        Content context = contextId == null ? null : contentById.get(contextId);
        if (context == null || context.getType() == ContentType.SEASON || !sameReference(context, source)) {
            throw new NotFoundException("Prepared person context reference not found");
        }
    }

    private boolean sameReference(Content content, PickContentTargetDTO target) {
        return target != null && target.type() != null
                && content.getType().name().equals(target.type().name())
                && Objects.equals(content.getTmdbId(), target.tmdbId())
                && Objects.equals(content.getSeriesTmdbId(), target.seriesTmdbId())
                && Objects.equals(content.getSeasonNumber(), target.seasonNumber())
                && Objects.equals(content.getEpisodeNumber(), target.episodeNumber());
    }

    private Content reference(Map<UUID, Content> contentById, UUID id) {
        return id == null ? null : contentById.get(id);
    }
    private ContentType contentType(PickAllowedType type) {
        if (type == PickAllowedType.PERSON) {
            throw new BadRequestException("Person categories do not use content options");
        }
        return type.toContentType();
    }

    private record Index(int category, int option) { }

    private record TargetIdentity(UUID contentId, String personTmdbId, UUID contextContentId) { }
}
